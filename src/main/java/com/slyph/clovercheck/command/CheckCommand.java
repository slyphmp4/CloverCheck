package com.slyph.clovercheck.command;

import com.slyph.clovercheck.CloverCheckPlugin;
import com.slyph.clovercheck.model.CheckOutcome;
import com.slyph.clovercheck.model.CheckSession;
import com.slyph.clovercheck.service.CheckService;
import com.slyph.clovercheck.service.MessageService;
import com.slyph.clovercheck.util.DurationParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CheckCommand implements CommandExecutor, TabCompleter {
    private final CloverCheckPlugin plugin;
    private final MessageService messages;
    private final CheckService checks;

    public CheckCommand(CloverCheckPlugin plugin, MessageService messages, CheckService checks) {
        this.plugin = plugin;
        this.messages = messages;
        this.checks = checks;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player && checks.isChecked(player.getUniqueId()) && !sender.hasPermission("clovercheck.staff")) messages.send(sender, "player-help");
            else messages.send(sender, "help");
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "start" -> start(sender, args);
            case "finish" -> finish(sender, args);
            case "cancel" -> cancel(sender, args);
            case "status" -> status(sender, args);
            case "list" -> list(sender);
            case "reload" -> reload(sender);
            case "admit" -> admit(sender);
            default -> {
                messages.send(sender, "help");
                yield true;
            }
        };
    }

    private boolean start(CommandSender sender, String[] args) {
        if (!permission(sender, "clovercheck.command.start")) return true;
        if (args.length < 2) {
            messages.send(sender, "help");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "player-not-found", Map.of("player", args[1]));
            return true;
        }
        if (sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId())) {
            messages.send(sender, "cannot-check-yourself");
            return true;
        }
        if (checks.isBypassed(target)) {
            messages.send(sender, "bypassed", Map.of("player", target.getName()));
            return true;
        }
        if (checks.isChecked(target.getUniqueId())) {
            messages.send(sender, "already-checking", Map.of("player", target.getName()));
            return true;
        }
        Duration duration = checks.defaultDuration();
        if (args.length >= 3) {
            Optional<Duration> parsed = DurationParser.parse(args[2]);
            if (parsed.isEmpty()) {
                messages.send(sender, "invalid-duration");
                return true;
            }
            duration = parsed.get();
        }
        if (duration.compareTo(checks.maximumDuration()) > 0) {
            messages.send(sender, "duration-too-long", Map.of("maximum", DurationParser.format(checks.maximumDuration())));
            return true;
        }
        checks.start(sender, target, duration);
        return true;
    }

    private boolean finish(CommandSender sender, String[] args) {
        if (!permission(sender, "clovercheck.command.finish")) return true;
        if (args.length < 3) {
            messages.send(sender, "help");
            return true;
        }
        Optional<CheckSession> session = checks.sessionByName(args[1]);
        if (session.isEmpty()) {
            messages.send(sender, "not-checking", Map.of("player", args[1]));
            return true;
        }
        CheckOutcome outcome = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "clean" -> CheckOutcome.CLEAN;
            case "cheats" -> CheckOutcome.CHEATS;
            case "refusal" -> CheckOutcome.REFUSAL;
            default -> null;
        };
        if (outcome == null) {
            messages.send(sender, "invalid-result");
            return true;
        }
        checks.finish(sender, session.get(), outcome);
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        if (!permission(sender, "clovercheck.command.cancel")) return true;
        if (args.length < 2) {
            messages.send(sender, "help");
            return true;
        }
        Optional<CheckSession> session = checks.sessionByName(args[1]);
        if (session.isEmpty()) {
            messages.send(sender, "not-checking", Map.of("player", args[1]));
            return true;
        }
        checks.finish(sender, session.get(), CheckOutcome.CANCELLED);
        return true;
    }

    private boolean status(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            if (!permission(sender, "clovercheck.command.status")) return true;
            Optional<CheckSession> session = checks.sessionByName(args[1]);
            if (session.isEmpty()) {
                messages.send(sender, "not-checking", Map.of("player", args[1]));
                return true;
            }
            messages.send(sender, "status", checks.placeholders(session.get(), Instant.now()));
            return true;
        }
        if (sender instanceof Player player) {
            Optional<CheckSession> own = checks.session(player.getUniqueId());
            if (own.isPresent()) {
                messages.send(sender, "status", checks.placeholders(own.get(), Instant.now()));
                return true;
            }
        }
        if (!permission(sender, "clovercheck.command.status")) return true;
        messages.send(sender, "help");
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!permission(sender, "clovercheck.command.list")) return true;
        List<CheckSession> sessions = checks.sessions();
        if (sessions.isEmpty()) {
            messages.send(sender, "list-empty");
            return true;
        }
        messages.send(sender, "list-header");
        for (CheckSession session : sessions) messages.send(sender, "list-entry", checks.placeholders(session, Instant.now()));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!permission(sender, "clovercheck.command.reload")) return true;
        if (plugin.reloadPlugin()) messages.send(sender, "reload-success");
        else messages.send(sender, "reload-failed");
        return true;
    }

    private boolean admit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (!permission(sender, "clovercheck.command.admit")) return true;
        checks.admit(player);
        return true;
    }

    private boolean permission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        messages.send(sender, "no-permission");
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            addIfAllowed(options, sender, "clovercheck.command.start", "start");
            addIfAllowed(options, sender, "clovercheck.command.finish", "finish");
            addIfAllowed(options, sender, "clovercheck.command.cancel", "cancel");
            addIfAllowed(options, sender, "clovercheck.command.status", "status");
            addIfAllowed(options, sender, "clovercheck.command.list", "list");
            addIfAllowed(options, sender, "clovercheck.command.reload", "reload");
            if (sender instanceof Player player && checks.isChecked(player.getUniqueId()) && sender.hasPermission("clovercheck.command.admit")) options.add("admit");
            return filter(options, args[0]);
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (subcommand.equals("start") && sender.hasPermission("clovercheck.command.start")) {
                return filter(Bukkit.getOnlinePlayers().stream().filter(player -> !checks.isChecked(player.getUniqueId())).filter(player -> !checks.isBypassed(player)).map(Player::getName).toList(), args[1]);
            }
            if ((subcommand.equals("finish") && sender.hasPermission("clovercheck.command.finish")) || (subcommand.equals("cancel") && sender.hasPermission("clovercheck.command.cancel")) || (subcommand.equals("status") && sender.hasPermission("clovercheck.command.status"))) {
                return filter(checks.sessions().stream().map(CheckSession::playerName).toList(), args[1]);
            }
        }
        if (args.length == 3 && subcommand.equals("finish") && sender.hasPermission("clovercheck.command.finish")) return filter(List.of("clean", "cheats", "refusal"), args[2]);
        if (args.length == 3 && subcommand.equals("start") && sender.hasPermission("clovercheck.command.start")) return filter(List.of("5m", "10m", "15m", "30m"), args[2]);
        return List.of();
    }

    private static void addIfAllowed(List<String> options, CommandSender sender, String permission, String option) {
        if (sender.hasPermission(permission)) options.add(option);
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
