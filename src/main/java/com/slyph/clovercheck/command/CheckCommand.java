package com.slyph.clovercheck.command;

import com.slyph.clovercheck.CloverCheck;
import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.service.CheckSessionService;
import com.slyph.clovercheck.service.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CheckCommand implements CommandExecutor, TabCompleter {
    private final CloverCheck plugin;
    private final ConfigService config;
    private final MessageService messages;
    private final CheckSessionService checks;

    public CheckCommand(CloverCheck plugin, ConfigService config, MessageService messages, CheckSessionService checks) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.checks = checks;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player && checks.isChecked(player.getUniqueId())) {
                checks.showOwnStatus(player);
            } else if (permission(sender, "clovercheck.use")) {
                messages.send(sender, "help");
            }
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        return switch (first) {
            case "clean" -> complete(sender, args, CheckResult.CLEAN);
            case "cheats" -> complete(sender, args, CheckResult.CHEATS_FOUND);
            case "refuse" -> complete(sender, args, CheckResult.REFUSED);
            case "cancel" -> cancel(sender, args);
            case "status" -> status(sender, args);
            case "list" -> list(sender);
            case "history" -> history(sender, args);
            case "info" -> info(sender, args);
            case "reload" -> reload(sender);
            case "confess" -> confess(sender, args);
            case "tp" -> teleport(sender, args);
            case "start" -> startCompatibility(sender, args);
            default -> startDirect(sender, args);
        };
    }

    private boolean startDirect(CommandSender sender, String[] args) {
        if (!startPermission(sender)) return true;
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(sender, "player-not-found", Map.of("player", args[0]));
            return true;
        }
        checks.start(sender, target, join(args, 1));
        return true;
    }

    private boolean startCompatibility(CommandSender sender, String[] args) {
        if (!startPermission(sender)) return true;
        if (args.length < 2) {
            messages.send(sender, "usage-start");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "player-not-found", Map.of("player", args[1]));
            return true;
        }
        checks.start(sender, target, join(args, 2));
        return true;
    }

    private boolean complete(CommandSender sender, String[] args, CheckResult result) {
        if (!permission(sender, "clovercheck.finish")) return true;
        if (args.length < 2) {
            messages.send(sender, "usage-finish");
            return true;
        }
        if (isSelfTarget(sender, args[1])) {
            messages.send(sender, "cannot-check-yourself");
            return true;
        }
        checks.staffComplete(sender, args[1], result, join(args, 2));
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        if (!permission(sender, "clovercheck.cancel")) return true;
        if (args.length < 2) {
            messages.send(sender, "usage-cancel");
            return true;
        }
        if (isSelfTarget(sender, args[1])) {
            messages.send(sender, "cannot-check-yourself");
            return true;
        }
        checks.staffComplete(sender, args[1], CheckResult.CANCELLED, join(args, 2));
        return true;
    }

    private boolean status(CommandSender sender, String[] args) {
        if (args.length == 1 && sender instanceof Player player && checks.isChecked(player.getUniqueId())) {
            checks.showOwnStatus(player);
            return true;
        }
        if (!permission(sender, "clovercheck.status")) return true;
        if (args.length < 2) {
            messages.send(sender, "usage-status");
            return true;
        }
        checks.showStatus(sender, args[1]);
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!permission(sender, "clovercheck.list")) return true;
        checks.showList(sender);
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (!permission(sender, "clovercheck.history")) return true;
        if (args.length < 2) {
            messages.send(sender, "usage-history");
            return true;
        }
        checks.showHistory(sender, args[1]);
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (!permission(sender, "clovercheck.info")) return true;
        if (args.length < 2) {
            messages.send(sender, "usage-info");
            return true;
        }
        checks.showInfo(sender, args[1]);
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!permission(sender, "clovercheck.reload")) return true;
        CloverCheck.ReloadOutcome outcome = plugin.reloadPlugin();
        if (!outcome.success()) {
            messages.send(sender, "reload-failed");
        } else if (outcome.databaseRestartRequired()) {
            messages.send(sender, "reload-restart-required");
        } else {
            messages.send(sender, "reload-success");
        }
        return true;
    }

    private boolean confess(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (!permission(sender, "clovercheck.confess")) return true;
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            checks.confirmConfess(player);
        } else {
            checks.requestConfess(player);
        }
        return true;
    }

    private boolean teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (!permission(sender, "clovercheck.teleport")) return true;
        if (args.length < 2) {
            messages.send(sender, "usage-teleport");
            return true;
        }
        checks.teleportModerator(player, args[1]);
        return true;
    }

    private boolean permission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        messages.send(sender, "no-permission");
        return false;
    }

    private boolean startPermission(CommandSender sender) {
        if (sender instanceof Player) {
            return permission(sender, "clovercheck.start");
        }
        if (isConsoleSender(sender)) {
            if (!config.settings().console().allowStartChecks()) {
                messages.send(sender, "player-only");
                return false;
            }
            return permission(sender, "clovercheck.start");
        }
        messages.send(sender, "player-only");
        return false;
    }

    private boolean hasStartAccess(CommandSender sender) {
        if (sender instanceof Player) {
            return sender.hasPermission("clovercheck.start");
        }
        return isConsoleSender(sender)
                && config.settings().console().allowStartChecks()
                && sender.hasPermission("clovercheck.start");
    }

    private static boolean isConsoleSender(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    private static boolean isSelfTarget(CommandSender sender, String playerName) {
        return sender instanceof Player player && player.getName().equalsIgnoreCase(playerName);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            add(options, sender, "clovercheck.finish", "clean", "cheats", "refuse");
            add(options, sender, "clovercheck.cancel", "cancel");
            add(options, sender, "clovercheck.status", "status");
            add(options, sender, "clovercheck.list", "list");
            add(options, sender, "clovercheck.history", "history");
            add(options, sender, "clovercheck.info", "info");
            add(options, sender, "clovercheck.reload", "reload");
            add(options, sender, "clovercheck.teleport", "tp");
            if (sender instanceof Player player && checks.isChecked(player.getUniqueId()) && sender.hasPermission("clovercheck.confess")) {
                options.add("confess");
            }
            if (hasStartAccess(sender)) {
                options.addAll(startablePlayers(sender));
            }
            return filter(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("confess") && sender instanceof Player player
                    && checks.isChecked(player.getUniqueId()) && sender.hasPermission("clovercheck.confess")) {
                return filter(List.of("confirm"), args[1]);
            }
            if (sub.equals("start") && hasStartAccess(sender)) {
                return filter(startablePlayers(sender), args[1]);
            }
            if (List.of("clean", "cheats", "refuse").contains(sub) && sender.hasPermission("clovercheck.finish")) {
                return filter(activeTargets(sender), args[1]);
            }
            if (sub.equals("cancel") && sender.hasPermission("clovercheck.cancel")) {
                return filter(activeTargets(sender), args[1]);
            }
            if (sub.equals("status") && sender.hasPermission("clovercheck.status")) {
                return filter(checks.activeSessions().stream().map(session -> session.playerName()).toList(), args[1]);
            }
            if (sub.equals("tp") && sender.hasPermission("clovercheck.teleport")) {
                return filter(checks.activeSessions().stream().map(session -> session.playerName()).toList(), args[1]);
            }
            if (sub.equals("history") && sender.hasPermission("clovercheck.history")) {
                return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            }
            if (sub.equals("info") && sender.hasPermission("clovercheck.info")) {
                return filter(checks.activeSessions().stream().map(session -> session.id()).toList(), args[1]);
            }
        }
        return List.of();
    }

    private List<String> startablePlayers(CommandSender sender) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> !checks.isChecked(player.getUniqueId()))
                .filter(player -> !(sender instanceof Player self) || !self.getUniqueId().equals(player.getUniqueId()))
                .filter(player -> !player.hasPermission("clovercheck.bypass") || sender.hasPermission("clovercheck.override-bypass"))
                .map(Player::getName)
                .toList();
    }

    private List<String> activeTargets(CommandSender sender) {
        return checks.activeSessions().stream()
                .map(session -> session.playerName())
                .filter(name -> !isSelfTarget(sender, name))
                .toList();
    }

    private static void add(List<String> options, CommandSender sender, String permission, String... values) {
        if (sender.hasPermission(permission)) options.addAll(List.of(values));
    }

    private static String join(String[] args, int from) {
        if (from >= args.length) return "";
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .distinct()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
