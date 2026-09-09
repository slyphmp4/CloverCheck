package com.slyph.clovercheck.service;

import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.model.AuditEventType;
import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.CheckState;
import com.slyph.clovercheck.model.QuitPolicy;
import com.slyph.clovercheck.model.StoredLocation;
import com.slyph.clovercheck.model.TimeoutPolicy;
import com.slyph.clovercheck.session.CheckSession;
import com.slyph.clovercheck.session.CheckSessionSnapshot;
import com.slyph.clovercheck.session.SessionRegistry;
import com.slyph.clovercheck.storage.CheckRepository;
import com.slyph.clovercheck.ui.CheckUiService;
import com.slyph.clovercheck.util.CommandNormalizer;
import com.slyph.clovercheck.util.DurationFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class CheckSessionService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final JavaPlugin plugin;
    private final ConfigService config;
    private final MessageService messages;
    private final CheckRepository repository;
    private final AuditService audit;
    private final ActionService actions;
    private final CheckUiService ui;
    private final SessionRegistry registry = new SessionRegistry();
    private final Map<UUID, Instant> confessConfirmations = new HashMap<>();
    private final Map<String, Long> reminderBuckets = new HashMap<>();
    private BukkitTask ticker;

    public CheckSessionService(
            JavaPlugin plugin,
            ConfigService config,
            MessageService messages,
            CheckRepository repository,
            AuditService audit,
            ActionService actions,
            CheckUiService ui,
            List<CheckSessionSnapshot> restored
    ) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.repository = repository;
        this.audit = audit;
        this.actions = actions;
        this.ui = ui;
        restore(restored);
    }

    public void startTicker() {
        if (ticker != null) {
            ticker.cancel();
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void resumeOnlineSessions() {
        Instant now = Instant.now();
        for (CheckSession session : registry.activeSessions()) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            boolean changed = false;
            if (session.state() == CheckState.STARTING) {
                changed = session.activate(now);
            } else if (session.state() == CheckState.DISCONNECTED) {
                changed = session.reconnect(player.getName(), now);
                if (changed) {
                    audit.log(session.id(), AuditEventType.PLAYER_REJOIN, player.getUniqueId(), player.getName(), "session restored after reconnect");
                }
            } else {
                changed = session.updatePlayerName(player.getName(), now);
            }
            if (changed) {
                persist(session);
            }
            player.closeInventory();
            Map<String, String> placeholders = placeholders(session.snapshot(), now);
            messages.send(player, "resumed-target", placeholders);
            ui.showResume(player, placeholders, progress(session, now));
        }
    }

    public void start(CommandSender moderator, Player target, String reason) {
        if (!moderator.hasPermission("clovercheck.start")) {
            messages.send(moderator, "no-permission");
            return;
        }
        if (moderator instanceof Player player && player.getUniqueId().equals(target.getUniqueId())) {
            messages.send(moderator, "cannot-check-yourself");
            return;
        }
        if (target.isDead()) {
            messages.send(moderator, "player-dead", Map.of("player", target.getName()));
            return;
        }
        if (target.hasPermission("clovercheck.bypass") && !moderator.hasPermission("clovercheck.override-bypass")) {
            messages.send(moderator, "bypassed", Map.of("player", target.getName()));
            return;
        }
        String normalizedReason = normalizeUserText(reason, config.settings().defaultReason(), PluginSettings.MAX_REASON_LENGTH);
        if (normalizedReason == null) {
            messages.send(moderator, "reason-too-long");
            return;
        }
        if (target.isInsideVehicle()) {
            target.leaveVehicle();
        }
        Instant now = Instant.now();
        Location location = target.getLocation();
        StoredLocation origin = new StoredLocation(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
        UUID moderatorId = moderator instanceof Player player ? player.getUniqueId() : null;
        String moderatorName = moderator instanceof Player player ? player.getName() : "CONSOLE";
        CheckSession session = CheckSession.create(
                target.getUniqueId(),
                target.getName(),
                moderatorId,
                moderatorName,
                now,
                config.settings().checkDuration(),
                normalizedReason,
                origin
        );
        SessionRegistry.RegisterResult registerResult = registry.register(session, config.settings().oneActivePerModerator());
        if (registerResult != SessionRegistry.RegisterResult.ADDED) {
            handleRegisterFailure(moderator, target, registerResult);
            return;
        }
        if (!session.activate(now)) {
            audit.log(session.id(), AuditEventType.INTERNAL_ERROR, moderatorId, moderatorName, "failed STARTING to ACTIVE transition");
            return;
        }
        persist(session);
        target.closeInventory();
        CheckSessionSnapshot snapshot = session.snapshot();
        Map<String, String> placeholders = placeholders(snapshot, now);
        messages.send(moderator, "start-success", placeholders);
        messages.send(target, "target-start", placeholders);
        ui.showStart(target, placeholders, 1.0F);
        notifyStaff("notify-start", placeholders, moderatorId);
        audit.log(session.id(), AuditEventType.START, moderatorId, moderatorName, "reason=" + normalizedReason);
    }

    public boolean staffComplete(CommandSender actor, String playerName, CheckResult result, String comment) {
        Optional<CheckSession> found = findActive(playerName);
        if (found.isEmpty()) {
            messages.send(actor, "not-checking", Map.of("player", playerName));
            return false;
        }
        String normalizedComment = normalizeUserText(comment, "", PluginSettings.MAX_COMMENT_LENGTH);
        if (normalizedComment == null) {
            messages.send(actor, "comment-too-long");
            return false;
        }
        CheckSession session = found.get();
        audit.log(session.id(), AuditEventType.STAFF_ACTION, actorId(actor), actorName(actor), "complete=" + result.name());
        return completeInternal(actor, session, result, normalizedComment, result != CheckResult.CANCELLED);
    }

    public void requestConfess(Player player) {
        PluginSettings.ConfessSettings settings = config.settings().confess();
        if (!settings.enabled()) {
            messages.send(player, "confess-disabled");
            return;
        }
        Optional<CheckSession> optional = registry.byPlayer(player.getUniqueId());
        if (optional.isEmpty()) {
            messages.send(player, "not-under-check");
            return;
        }
        Instant expires = Instant.now().plusSeconds(settings.confirmationSeconds());
        confessConfirmations.put(player.getUniqueId(), expires);
        CheckSession session = optional.get();
        audit.log(session.id(), AuditEventType.CONFESS_REQUEST, player.getUniqueId(), player.getName(), "confirmation requested");
        messages.send(player, "confess-confirm", Map.of("seconds", Integer.toString(settings.confirmationSeconds())));
    }

    public void confirmConfess(Player player) {
        Optional<CheckSession> optional = registry.byPlayer(player.getUniqueId());
        if (optional.isEmpty()) {
            messages.send(player, "not-under-check");
            return;
        }
        Instant expires = confessConfirmations.remove(player.getUniqueId());
        if (expires == null || !expires.isAfter(Instant.now())) {
            messages.send(player, "confess-expired");
            return;
        }
        CheckSession session = optional.get();
        audit.log(session.id(), AuditEventType.CONFESS_CONFIRM, player.getUniqueId(), player.getName(), "confession confirmed");
        completeInternal(player, session, CheckResult.CONFESSED, "", true);
    }

    public void handleQuit(Player player) {
        Optional<CheckSession> optional = registry.byPlayer(player.getUniqueId());
        if (optional.isEmpty()) {
            handleModeratorQuit(player);
            return;
        }
        CheckSession session = optional.get();
        Instant now = Instant.now();
        if (!session.disconnect(now)) {
            return;
        }
        confessConfirmations.remove(player.getUniqueId());
        persist(session);
        ui.hide(player);
        Map<String, String> placeholders = placeholders(session.snapshot(), now);
        audit.log(session.id(), AuditEventType.PLAYER_QUIT, player.getUniqueId(), player.getName(), "quit policy=" + config.settings().quitPolicy().name());
        notifyModerator(session, "quit-moderator", placeholders);
        notifyStaff("quit-staff", placeholders, session.moderatorId());
        QuitPolicy policy = config.settings().quitPolicy();
        if (policy == QuitPolicy.MARK_AS_LEFT) {
            completeInternal(Bukkit.getConsoleSender(), session, CheckResult.LEFT, "", false);
        } else if (policy == QuitPolicy.EXECUTE_COMMANDS) {
            completeInternal(Bukkit.getConsoleSender(), session, CheckResult.LEFT, "", true);
        }
    }

    public void handleJoin(Player player) {
        Optional<CheckSession> optional = registry.byPlayer(player.getUniqueId());
        if (optional.isEmpty()) {
            return;
        }
        CheckSession session = optional.get();
        Instant now = Instant.now();
        boolean changed = session.state() == CheckState.DISCONNECTED
                ? session.reconnect(player.getName(), now)
                : session.updatePlayerName(player.getName(), now);
        if (changed) {
            persist(session);
        }
        player.closeInventory();
        Map<String, String> placeholders = placeholders(session.snapshot(), now);
        messages.send(player, "resumed-target", placeholders);
        ui.showResume(player, placeholders, progress(session, now));
        notifyModerator(session, "resumed-moderator", placeholders);
        notifyStaff("resumed-staff", placeholders, session.moderatorId());
        audit.log(session.id(), AuditEventType.PLAYER_REJOIN, player.getUniqueId(), player.getName(), "session resumed");
    }

    public void handleRespawn(Player player) {
        registry.byPlayer(player.getUniqueId()).ifPresent(session -> {
            Map<String, String> placeholders = placeholders(session.snapshot(), Instant.now());
            ui.showResume(player, placeholders, progress(session, Instant.now()));
        });
    }

    public void handleCheckedDeath(Player player) {
        registry.byPlayer(player.getUniqueId()).ifPresent(session ->
                audit.log(session.id(), AuditEventType.INTERNAL_ERROR, player.getUniqueId(), player.getName(), "checked player died while session was active"));
    }

    public boolean isChecked(UUID playerId) {
        return registry.byPlayer(playerId).isPresent();
    }

    public boolean isCommandAllowed(String rawCommand) {
        return CommandNormalizer.isAllowed(rawCommand, config.settings().commandWhitelist());
    }

    public PluginSettings.FreezeSettings freezeSettings() {
        return config.settings().freeze();
    }

    public List<CheckSession> activeSessions() {
        return registry.activeSessions();
    }

    public Optional<CheckSession> findActive(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            Optional<CheckSession> byUuid = registry.byPlayer(online.getUniqueId());
            if (byUuid.isPresent()) {
                return byUuid;
            }
        }
        return registry.byName(playerName);
    }

    public void showStatus(CommandSender sender, String playerName) {
        Optional<CheckSession> optional = findActive(playerName);
        if (optional.isEmpty()) {
            messages.send(sender, "not-checking", Map.of("player", playerName));
            return;
        }
        showStatus(sender, optional.get());
    }

    public void showOwnStatus(Player player) {
        Optional<CheckSession> optional = registry.byPlayer(player.getUniqueId());
        if (optional.isEmpty()) {
            messages.send(player, "not-under-check");
            return;
        }
        showStatus(player, optional.get());
    }

    public void showList(CommandSender sender) {
        List<CheckSession> sessions = registry.activeSessions();
        if (sessions.isEmpty()) {
            messages.send(sender, "list-empty");
            return;
        }
        messages.send(sender, "list-header");
        Instant now = Instant.now();
        for (CheckSession session : sessions) {
            messages.send(sender, "list-entry", placeholders(session.snapshot(), now));
        }
    }

    public void showHistory(CommandSender sender, String query) {
        messages.send(sender, "history-loading", Map.of("player", query));
        int limit = config.settings().database().historyLimit();
        repository.history(query, limit).whenComplete((history, error) -> runSync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to query CloverCheck history", unwrap(error));
                messages.send(sender, "storage-error");
                return;
            }
            if (history.isEmpty()) {
                messages.send(sender, "history-empty", Map.of("player", query));
                return;
            }
            messages.send(sender, "history-header", Map.of("player", query));
            for (CheckSessionSnapshot snapshot : history) {
                messages.send(sender, "history-entry", placeholders(snapshot, Instant.now()));
            }
        }));
    }

    public void showInfo(CommandSender sender, String id) {
        repository.findById(id).whenComplete((snapshot, error) -> runSync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to query CloverCheck session info", unwrap(error));
                messages.send(sender, "storage-error");
                return;
            }
            if (snapshot.isEmpty()) {
                messages.send(sender, "info-not-found", Map.of("id", id));
                return;
            }
            messages.send(sender, "info", placeholders(snapshot.get(), Instant.now()));
        }));
    }

    public void teleportModerator(Player moderator, String playerName) {
        if (!config.settings().teleport().moderatorToPlayerEnabled()) {
            messages.send(moderator, "teleport-disabled");
            return;
        }
        Optional<CheckSession> optional = findActive(playerName);
        if (optional.isEmpty()) {
            messages.send(moderator, "not-checking", Map.of("player", playerName));
            return;
        }
        Player target = Bukkit.getPlayer(optional.get().playerId());
        if (target == null || !target.isOnline()) {
            messages.send(moderator, "player-offline", Map.of("player", optional.get().playerName()));
            return;
        }
        moderator.teleport(target.getLocation());
        audit.log(optional.get().id(), AuditEventType.STAFF_ACTION, moderator.getUniqueId(), moderator.getName(), "teleport to checked player");
        messages.send(moderator, "teleport-success", Map.of("player", target.getName()));
    }

    public void onSettingsReload() {
        for (CheckSession session : registry.activeSessions()) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player != null && player.isOnline()) {
                Map<String, String> placeholders = placeholders(session.snapshot(), Instant.now());
                ui.update(player, placeholders, progress(session, Instant.now()));
            }
        }
    }

    public List<CheckSessionSnapshot> shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        ui.hideAll(Bukkit.getOnlinePlayers());
        confessConfirmations.clear();
        reminderBuckets.clear();
        return registry.activeSessions().stream().map(CheckSession::snapshot).toList();
    }

    private void restore(List<CheckSessionSnapshot> restored) {
        for (CheckSessionSnapshot snapshot : restored) {
            try {
                CheckSession session = CheckSession.restore(snapshot);
                if (!registry.restore(session)) {
                    plugin.getLogger().warning("Ignored duplicate active CloverCheck session " + snapshot.id());
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignored malformed CloverCheck session " + snapshot.id() + ": " + exception.getMessage());
            }
        }
    }

    private void tick() {
        Instant now = Instant.now();
        int reminderInterval = config.settings().reminderIntervalSeconds();
        for (CheckSession session : new ArrayList<>(registry.activeSessions())) {
            if (!session.deadlineAt().isAfter(now)) {
                boolean execute = config.settings().timeoutPolicy() == TimeoutPolicy.EXECUTE_COMMANDS;
                audit.log(session.id(), AuditEventType.TIMEOUT, null, "CONSOLE", "check duration expired");
                completeInternal(Bukkit.getConsoleSender(), session, CheckResult.TIMEOUT, "", execute);
                continue;
            }
            if (session.state() != CheckState.ACTIVE) {
                continue;
            }
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            Map<String, String> placeholders = placeholders(session.snapshot(), now);
            ui.update(player, placeholders, progress(session, now));
            if (reminderInterval > 0) {
                long bucket = session.remaining(now).toSeconds() / reminderInterval;
                Long previous = reminderBuckets.put(session.id(), bucket);
                if (previous != null && bucket < previous) {
                    messages.send(player, "reminder", placeholders);
                }
            }
        }
    }

    private boolean completeInternal(CommandSender actor, CheckSession session, CheckResult result, String comment, boolean executeActions) {
        Instant now = Instant.now();
        if (!session.complete(result, comment, now)) {
            return false;
        }
        registry.removeTerminal(session);
        confessConfirmations.remove(session.playerId());
        reminderBuckets.remove(session.id());
        persist(session);
        CheckSessionSnapshot snapshot = session.snapshot();
        Map<String, String> placeholders = placeholders(snapshot, now);
        Player target = Bukkit.getPlayer(session.playerId());
        if (target != null && target.isOnline()) {
            ui.complete(target, placeholders);
            messages.send(target, resultMessage("complete-target", result), placeholders);
        }
        messages.send(actor, resultMessage("complete-actor", result), placeholders);
        notifyModerator(session, resultMessage("complete-moderator", result), placeholders);
        notifyStaff(resultMessage("complete-staff", result), placeholders, session.moderatorId());
        AuditEventType eventType = result == CheckResult.CANCELLED ? AuditEventType.SESSION_CANCEL : AuditEventType.SESSION_COMPLETE;
        audit.log(session.id(), eventType, actorId(actor), actorName(actor), "result=" + result.name());
        if (executeActions) {
            actions.execute(snapshot, result, actor);
        }
        return true;
    }

    private void showStatus(CommandSender sender, CheckSession session) {
        Map<String, String> placeholders = placeholders(session.snapshot(), Instant.now());
        messages.send(sender, "status", placeholders);
        Component controls = Component.empty();
        boolean hasControl = false;
        if (sender.hasPermission("clovercheck.finish")) {
            controls = appendControl(controls, hasControl, messages.component("ui.controls.clean", placeholders)
                    .hoverEvent(HoverEvent.showText(messages.component("ui.controls.clean-hover", placeholders)))
                    .clickEvent(ClickEvent.suggestCommand("/check clean " + session.playerName())));
            hasControl = true;
            controls = appendControl(controls, true, messages.component("ui.controls.cheats", placeholders)
                    .hoverEvent(HoverEvent.showText(messages.component("ui.controls.cheats-hover", placeholders)))
                    .clickEvent(ClickEvent.suggestCommand("/check cheats " + session.playerName() + " ")));
        }
        if (sender.hasPermission("clovercheck.cancel")) {
            controls = appendControl(controls, hasControl, messages.component("ui.controls.cancel", placeholders)
                    .hoverEvent(HoverEvent.showText(messages.component("ui.controls.cancel-hover", placeholders)))
                    .clickEvent(ClickEvent.suggestCommand("/check cancel " + session.playerName() + " ")));
            hasControl = true;
        }
        if (sender instanceof Player && sender.hasPermission("clovercheck.teleport") && config.settings().teleport().moderatorToPlayerEnabled()) {
            controls = appendControl(controls, hasControl, messages.component("ui.controls.teleport", placeholders)
                    .hoverEvent(HoverEvent.showText(messages.component("ui.controls.teleport-hover", placeholders)))
                    .clickEvent(ClickEvent.runCommand("/check tp " + session.playerName())));
            hasControl = true;
        }
        if (hasControl) {
            sender.sendMessage(controls);
        }
    }

    private Component appendControl(Component current, boolean separator, Component control) {
        if (separator) {
            current = current.append(messages.component("ui.controls.separator", Map.of()));
        }
        return current.append(control);
    }

    private void handleRegisterFailure(CommandSender moderator, Player target, SessionRegistry.RegisterResult result) {
        switch (result) {
            case PLAYER_BUSY -> messages.send(moderator, "already-checking", Map.of("player", target.getName()));
            case MODERATOR_BUSY -> messages.send(moderator, "moderator-busy");
            case TERMINAL -> messages.send(moderator, "internal-error");
            case ADDED -> {
            }
        }
    }

    private void handleModeratorQuit(Player moderator) {
        for (CheckSession session : registry.forModerator(moderator.getUniqueId())) {
            Map<String, String> placeholders = placeholders(session.snapshot(), Instant.now());
            Player target = Bukkit.getPlayer(session.playerId());
            if (target != null && target.isOnline()) {
                messages.send(target, "moderator-left-target", placeholders);
            }
            notifyStaff("moderator-left-staff", placeholders, moderator.getUniqueId());
            audit.log(session.id(), AuditEventType.STAFF_ACTION, moderator.getUniqueId(), moderator.getName(), "moderator disconnected");
        }
    }

    private void notifyModerator(CheckSession session, String messageKey, Map<String, String> placeholders) {
        if (session.moderatorId() == null) {
            return;
        }
        Player moderator = Bukkit.getPlayer(session.moderatorId());
        if (moderator != null && moderator.isOnline()) {
            messages.send(moderator, messageKey, placeholders);
        }
    }

    private void notifyStaff(String messageKey, Map<String, String> placeholders, UUID excluded) {
        if (!config.settings().staff().notifications()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("clovercheck.notify")) {
                continue;
            }
            if (excluded != null && excluded.equals(player.getUniqueId())) {
                continue;
            }
            messages.send(player, messageKey, placeholders);
        }
    }

    private Map<String, String> placeholders(CheckSessionSnapshot snapshot, Instant now) {
        Map<String, String> placeholders = new HashMap<>();
        Player online = Bukkit.getPlayer(snapshot.playerId());
        placeholders.put("id", snapshot.id());
        placeholders.put("short_id", snapshot.id().substring(0, Math.min(8, snapshot.id().length())).toUpperCase(Locale.ROOT));
        placeholders.put("player", snapshot.playerName());
        placeholders.put("moderator", snapshot.moderatorName());
        placeholders.put("reason", snapshot.reason());
        placeholders.put("comment", snapshot.comment().isBlank() ? "-" : snapshot.comment());
        placeholders.put("time", DurationFormatter.format(remaining(snapshot, now)));
        placeholders.put("duration", DurationFormatter.format(Duration.between(snapshot.startedAt(), snapshot.deadlineAt())));
        placeholders.put("contact", config.settings().contact());
        placeholders.put("started", DATE_TIME.format(snapshot.startedAt().atZone(ZoneId.systemDefault())));
        placeholders.put("ended", snapshot.endedAt() == null ? "-" : DATE_TIME.format(snapshot.endedAt().atZone(ZoneId.systemDefault())));
        placeholders.put("state", snapshot.state().name());
        placeholders.put("result", snapshot.result() == null ? "-" : snapshot.result().name());
        placeholders.put("online", online != null && online.isOnline() ? "online" : "offline");
        placeholders.put("world", online != null && online.isOnline() ? online.getWorld().getName() : "-");
        return placeholders;
    }

    private static Duration remaining(CheckSessionSnapshot snapshot, Instant now) {
        Duration remaining = Duration.between(now, snapshot.deadlineAt());
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private static float progress(CheckSession session, Instant now) {
        long total = Duration.between(session.startedAt(), session.deadlineAt()).toMillis();
        if (total <= 0L) {
            return 0.0F;
        }
        return (float) session.remaining(now).toMillis() / (float) total;
    }

    private void persist(CheckSession session) {
        repository.upsert(session.snapshot()).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist CloverCheck session " + session.id(), unwrap(error));
            audit.log(session.id(), AuditEventType.INTERNAL_ERROR, null, "CONSOLE", "session persistence failed");
            return null;
        });
    }

    private void runSync(Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static String normalizeUserText(String value, String fallback, int maxLength) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.length() > maxLength) {
            return null;
        }
        return normalized.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    private static String resultMessage(String prefix, CheckResult result) {
        return prefix + "." + result.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private static String actorName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "CONSOLE";
    }
}
