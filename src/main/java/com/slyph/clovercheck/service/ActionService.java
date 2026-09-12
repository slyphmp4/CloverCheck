package com.slyph.clovercheck.service;

import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.model.AuditEventType;
import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.session.CheckSessionSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import java.util.logging.Level;

public final class ActionService {
    private static final Pattern UNRESOLVED = Pattern.compile("%[a-zA-Z0-9_]+%");

    private final ConfigService config;
    private final AuditService audit;
    private final Logger logger;

    public ActionService(ConfigService config, AuditService audit, Logger logger) {
        this.config = config;
        this.audit = audit;
        this.logger = logger;
    }

    public void execute(CheckSessionSnapshot session, CheckResult result, CommandSender actor) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("%player%", safeName(session.playerName()));
        placeholders.put("%player_uuid%", session.playerId().toString());
        placeholders.put("%moderator%", safeName(session.moderatorName()));
        placeholders.put("%session_id%", session.id());

        for (String template : config.settings().actionCommands(result)) {
            String command = template == null ? "" : template.trim();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                command = command.replace(entry.getKey(), entry.getValue());
            }
            if (command.isBlank()) {
                continue;
            }
            if (containsControl(command) || UNRESOLVED.matcher(command).find()) {
                logger.warning("Skipped unsafe CloverCheck action command for session " + session.id());
                audit.log(session.id(), AuditEventType.INTERNAL_ERROR, actorId(actor), actorName(actor), "unsafe configured action skipped");
                continue;
            }
            String root = command.split("\\s+", 2)[0];
            audit.log(session.id(), AuditEventType.COMMAND_EXECUTION, actorId(actor), actorName(actor), "root=" + root);
            try {
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (!dispatched) {
                    logger.warning("CloverCheck action command was not handled for session " + session.id() + ": root=" + root);
                    audit.log(session.id(), AuditEventType.INTERNAL_ERROR, actorId(actor), actorName(actor), "action command not handled root=" + root);
                }
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "CloverCheck action command failed for session " + session.id() + ": root=" + root, exception);
                audit.log(session.id(), AuditEventType.INTERNAL_ERROR, actorId(actor), actorName(actor), "action command failed root=" + root);
            }
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character) && character != ' ');
    }

    private static String safeName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,16}")) {
            return "CONSOLE";
        }
        return value;
    }

    private static UUID actorId(CommandSender sender) {
        return sender instanceof org.bukkit.entity.Player player ? player.getUniqueId() : null;
    }

    private static String actorName(CommandSender sender) {
        return sender instanceof org.bukkit.entity.Player player ? player.getName() : "CONSOLE";
    }
}
