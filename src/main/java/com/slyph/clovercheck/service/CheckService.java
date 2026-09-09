package com.slyph.clovercheck.service;

import com.slyph.clovercheck.CloverCheckPlugin;
import com.slyph.clovercheck.model.CheckOutcome;
import com.slyph.clovercheck.model.CheckSession;
import com.slyph.clovercheck.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CheckService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final CloverCheckPlugin plugin;
    private final MessageService messages;
    private final CheckStorage storage;
    private final Map<UUID, CheckSession> sessions = new LinkedHashMap<>();
    private Duration defaultDuration = Duration.ofMinutes(10);
    private Duration maximumDuration = Duration.ofMinutes(30);
    private int reminderIntervalSeconds = 60;
    private String discord = "discord.gg/example";
    private String notifyPermission = "clovercheck.notify";
    private String bypassPermission = "clovercheck.bypass";
    private String disconnectMode = "KEEP";
    private Set<String> allowedCommands = Set.of("check", "clovercheck");
    private BukkitTask ticker;

    public CheckService(CloverCheckPlugin plugin, MessageService messages, CheckStorage storage, Collection<CheckSession> restored) {
        this.plugin = plugin;
        this.messages = messages;
        this.storage = storage;
        for (CheckSession session : restored) {
            sessions.put(session.playerId(), session);
        }
        reloadSettings();
    }

    public void reloadSettings() {
        defaultDuration = durationSetting("check.default-duration", Duration.ofMinutes(10));
        maximumDuration = durationSetting("check.maximum-duration", Duration.ofMinutes(30));
        if (maximumDuration.compareTo(defaultDuration) < 0) {
            plugin.getLogger().warning("check.maximum-duration is lower than check.default-duration; using the default duration as the maximum.");
            maximumDuration = defaultDuration;
        }
        reminderIntervalSeconds = Math.max(0, plugin.getConfig().getInt("check.reminder-interval-seconds", 60));
        discord = plugin.getConfig().getString("check.discord", "discord.gg/example");
        notifyPermission = nonBlank(plugin.getConfig().getString("check.notify-permission"), "clovercheck.notify");
        bypassPermission = nonBlank(plugin.getConfig().getString("check.bypass-permission"), "clovercheck.bypass");
        disconnectMode = nonBlank(plugin.getConfig().getString("disconnect.mode"), "KEEP").toUpperCase(Locale.ROOT);
        if (!disconnectMode.equals("KEEP") && !disconnectMode.equals("FAIL")) {
            plugin.getLogger().warning("disconnect.mode must be KEEP or FAIL; using KEEP.");
            disconnectMode = "KEEP";
        }

        Set<String> commands = new HashSet<>();
        for (String command : plugin.getConfig().getStringList("check.allowed-commands")) {
            String normalized = normalizeCommand(command);
            if (!normalized.isBlank()) {
                commands.add(normalized);
            }
        }
        commands.add("check");
        commands.add("clovercheck");
        allowedCommands = Set.copyOf(commands);
    }

    public void startTicker() {
        if (ticker != null) {
            ticker.cancel();
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void resumeOnlineSessions() {
        Instant now = Instant.now();
        for (CheckSession session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player != null && player.isOnline()) {
                player.closeInventory();
                messages.send(player, "resumed-target", placeholders(session, now));
            }
        }
    }

    public Duration defaultDuration() {
        return defaultDuration;
    }

    public Duration maximumDuration() {
        return maximumDuration;
    }

    public boolean isChecked(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public boolean isBypassed(Player player) {
        return player.hasPermission(bypassPermission);
    }

    public Optional<CheckSession> session(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public Optional<CheckSession> sessionByName(String name) {
        return sessions.values().stream()
                .filter(session -> session.playerName().equalsIgnoreCase(name))
                .findFirst();
    }

    public List<CheckSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public CheckSession start(CommandSender staff, Player target, Duration duration) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        UUID staffId = staff instanceof Player player ? player.getUniqueId() : null;
        CheckSession session = new CheckSession(id, target.getUniqueId(), target.getName(), staffId, senderName(staff), now, now.plus(duration));
        sessions.put(target.getUniqueId(), session);
        target.closeInventory();
        if (target.isInsideVehicle()) {
            target.leaveVehicle();
        }
        storage.saveAsync(sessions.values());
        Map<String, String> placeholders = placeholders(session, now);
        messages.send(staff, "start-success", placeholders);
        messages.send(target, "target-start", placeholders);
        notifyStaff("notify-start", "staff-notify-start", placeholders);
        plugin.getLogger().info(staff.getName() + " started cheat check " + session.id() + " for " + target.getName());
        return session;
    }

    public void finish(CommandSender staff, CheckSession session, CheckOutcome outcome) {
        finishInternal(staff, session, outcome, true);
    }

    public void admit(Player player) {
        CheckSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            messages.send(player, "not-checking", Map.of("player", player.getName()));
            return;
        }
        messages.send(player, "admitted", placeholders(session, Instant.now()));
        finishInternal(Bukkit.getConsoleSender(), session, CheckOutcome.ADMITTED, false);
        notifyStaff("notify-admitted", null, placeholders(session, Instant.now()));
    }

    public void handleQuit(Player player) {
        CheckSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        Map<String, String> placeholders = placeholders(session, Instant.now());
        if (disconnectMode.equals("FAIL")) {
            notifyStaff("quit-fail", "staff-notify-quit", placeholders);
            finishInternal(Bukkit.getConsoleSender(), session, CheckOutcome.DISCONNECTED, false);
        } else {
            notifyStaff("quit-keep", "staff-notify-quit", placeholders);
            storage.saveAsync(sessions.values());
        }
    }

    public void handleJoin(Player player) {
        CheckSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        player.closeInventory();
        Map<String, String> placeholders = placeholders(session, Instant.now());
        messages.send(player, "resumed-target", placeholders);
        notifyStaff("resumed-staff", "staff-notify-start", placeholders);
    }

    public boolean isCommandAllowed(String rawCommand) {
        String root = normalizeCommand(rawCommand);
        if (allowedCommands.contains(root)) {
            return true;
        }
        int namespace = root.indexOf(':');
        return namespace >= 0 && namespace + 1 < root.length() && allowedCommands.contains(root.substring(namespace + 1));
    }

    public boolean freezeEnabled(String key) {
        return plugin.getConfig().getBoolean("freeze." + key, true);
    }

    public Map<String, String> placeholders(CheckSession session, Instant now) {
        Map<String, String> placeholders = new HashMap<>();
        Player online = Bukkit.getPlayer(session.playerId());
        placeholders.put("id", session.id());
        placeholders.put("player", session.playerName());
        placeholders.put("staff", session.staffName());
        placeholders.put("duration", DurationParser.format(session.totalDuration()));
        placeholders.put("time", DurationParser.format(session.remaining(now)));
        placeholders.put("maximum", DurationParser.format(maximumDuration));
        placeholders.put("discord", discord);
        placeholders.put("started", TIME_FORMAT.format(session.startedAt().atZone(ZoneId.systemDefault())));
        placeholders.put("online", online != null && online.isOnline() ? "в сети" : "не в сети");
        placeholders.put("world", online != null && online.isOnline() ? online.getWorld().getName() : "—");
        return placeholders;
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        storage.shutdown(sessions.values());
    }

    private void tick() {
        Instant now = Instant.now();
        for (CheckSession session : new ArrayList<>(sessions.values())) {
            if (!session.deadlineAt().isAfter(now)) {
                Player player = Bukkit.getPlayer(session.playerId());
                Map<String, String> placeholders = placeholders(session, now);
                if (player != null && player.isOnline()) {
                    messages.send(player, "timeout-target", placeholders);
                }
                notifyStaff("timeout-staff", "staff-notify-start", placeholders);
                finishInternal(Bukkit.getConsoleSender(), session, CheckOutcome.TIMEOUT, false);
                continue;
            }
            if (reminderIntervalSeconds > 0) {
                long remaining = session.remainingSeconds(now);
                if (remaining > 0 && remaining % reminderIntervalSeconds == 0) {
                    Player player = Bukkit.getPlayer(session.playerId());
                    if (player != null && player.isOnline()) {
                        messages.send(player, "reminder", placeholders(session, now));
                    }
                }
            }
        }
    }

    private void finishInternal(CommandSender staff, CheckSession session, CheckOutcome outcome, boolean sendStaffResult) {
        if (sessions.remove(session.playerId()) == null) {
            return;
        }
        storage.saveAsync(sessions.values());
        Instant now = Instant.now();
        Map<String, String> placeholders = placeholders(session, now);
        Player target = Bukkit.getPlayer(session.playerId());
        switch (outcome) {
            case CLEAN -> {
                if (sendStaffResult) messages.send(staff, "finish-clean-staff", placeholders);
                if (target != null && target.isOnline()) messages.send(target, "finish-clean-target", placeholders);
            }
            case CHEATS -> {
                if (sendStaffResult) messages.send(staff, "finish-cheats-staff", placeholders);
                if (target != null && target.isOnline()) messages.send(target, "finish-cheats-target", placeholders);
            }
            case REFUSAL -> {
                if (sendStaffResult) messages.send(staff, "finish-refusal-staff", placeholders);
                if (target != null && target.isOnline()) messages.send(target, "finish-refusal-target", placeholders);
            }
            case CANCELLED -> {
                if (sendStaffResult) messages.send(staff, "cancel-staff", placeholders);
                if (target != null && target.isOnline()) messages.send(target, "cancel-target", placeholders);
            }
            case ADMITTED, TIMEOUT, DISCONNECTED -> {
            }
        }
        executeActions(outcome, session, staff);
        plugin.getLogger().info("Cheat check " + session.id() + " for " + session.playerName() + " finished with " + outcome.name());
    }

    private void executeActions(CheckOutcome outcome, CheckSession session, CommandSender staff) {
        String actionKey = outcome.actionKey();
        if (actionKey == null || !plugin.getConfig().getBoolean("actions." + actionKey + ".enabled", false)) {
            return;
        }
        Map<String, String> placeholders = placeholders(session, Instant.now());
        placeholders.put("actor", senderName(staff));
        for (String configured : plugin.getConfig().getStringList("actions." + actionKey + ".commands")) {
            String command = configured;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                command = command.replace('%' + entry.getKey() + '%', entry.getValue());
            }
            command = command.trim();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (!command.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        }
    }

    private void notifyStaff(String messageKey, String hoverKey, Map<String, String> placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(notifyPermission)) continue;
            if (hoverKey == null) messages.send(player, messageKey, placeholders);
            else messages.sendInteractive(player, messageKey, hoverKey, placeholders);
        }
    }

    private Duration durationSetting(String path, Duration fallback) {
        String raw = plugin.getConfig().getString(path);
        Optional<Duration> parsed = DurationParser.parse(raw);
        if (parsed.isPresent()) return parsed.get();
        plugin.getLogger().warning(path + " is invalid; using " + DurationParser.format(fallback));
        return fallback;
    }

    private static String normalizeCommand(String command) {
        String value = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("/")) value = value.substring(1);
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "Консоль";
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
