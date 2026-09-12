package com.slyph.clovercheck.chat;

import com.slyph.clovercheck.service.CheckSessionService;
import com.slyph.clovercheck.service.MessageService;
import com.slyph.clovercheck.session.CheckSession;
import com.slyph.clovercheck.util.PluginTasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class CheckChatService {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final CheckSessionService checks;
    private final File file;
    private final Logger logger;
    private volatile Settings settings;

    public CheckChatService(JavaPlugin plugin, MessageService messages, CheckSessionService checks) {
        this.plugin = plugin;
        this.messages = messages;
        this.checks = checks;
        this.file = new File(plugin.getDataFolder(), "chat.yml");
        this.logger = plugin.getLogger();
    }

    public boolean reload() {
        try {
            prepareReload().run();
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            logger.severe("Failed to load chat.yml: " + exception.getMessage());
            return false;
        }
    }

    public Runnable prepareReload() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        Settings loaded = new Settings(
                requiredBoolean(config, "enabled"),
                requiredInt(config, "max-message-length", 1, 1024),
                requiredString(config, "formats.checked"),
                requiredString(config, "formats.moderator"),
                requiredLines(config, "messages.peer-offline"),
                requiredLines(config, "messages.ambiguous"),
                requiredLines(config, "messages.session-changed"),
                requiredLines(config, "messages.message-too-long")
        );
        return () -> settings = loaded;
    }

    public boolean intercept(UUID senderId, Component rawMessage) {
        Settings current = settings;
        if (current == null || !current.enabled()) {
            return false;
        }

        CheckChatRouter.Resolution route = CheckChatRouter.resolve(checks.activeSessions(), senderId);
        if (route.status() == CheckChatRouter.Status.NONE) {
            return false;
        }

        String message = sanitize(PLAIN.serialize(rawMessage));
        String expectedSessionId = route.status() == CheckChatRouter.Status.ACTIVE ? route.session().id() : null;
        PluginTasks.runSync(plugin, () -> deliver(senderId, expectedSessionId, message));
        return true;
    }

    private void deliver(UUID senderId, String expectedSessionId, String message) {
        Player sender = Bukkit.getPlayer(senderId);
        if (sender == null || !sender.isOnline()) {
            return;
        }

        Settings currentSettings = settings;
        if (currentSettings == null || !currentSettings.enabled()) {
            sendLines(sender, currentSettings == null ? List.of() : currentSettings.sessionChanged(), Map.of());
            return;
        }

        CheckChatRouter.Resolution current = CheckChatRouter.resolve(checks.activeSessions(), senderId);
        if (current.status() == CheckChatRouter.Status.AMBIGUOUS) {
            sendLines(sender, currentSettings.ambiguous(), Map.of());
            return;
        }
        if (current.status() != CheckChatRouter.Status.ACTIVE
                || expectedSessionId == null
                || !expectedSessionId.equals(current.session().id())) {
            sendLines(sender, currentSettings.sessionChanged(), Map.of());
            return;
        }
        if (message.isBlank()) {
            return;
        }
        if (message.length() > currentSettings.maxMessageLength()) {
            sendLines(sender, currentSettings.messageTooLong(), Map.of("maximum", Integer.toString(currentSettings.maxMessageLength())));
            return;
        }

        CheckSession session = current.session();
        boolean moderator = current.role() == CheckChatRouter.Role.MODERATOR;
        UUID peerId = moderator ? session.playerId() : session.moderatorId();
        String peerName = moderator ? session.playerName() : session.moderatorName();
        String shortId = session.id().substring(0, Math.min(8, session.id().length())).toUpperCase(java.util.Locale.ROOT);
        Map<String, String> placeholders = Map.of(
                "sender", sender.getName(),
                "message", message,
                "player", session.playerName(),
                "moderator", session.moderatorName(),
                "peer", peerName,
                "short_id", shortId
        );

        String format = moderator ? currentSettings.moderatorFormat() : currentSettings.checkedFormat();
        Component rendered = messages.render(format, placeholders);
        sender.sendMessage(rendered);

        Player peer = peerId == null ? null : Bukkit.getPlayer(peerId);
        if (peer != null && peer.isOnline()) {
            peer.sendMessage(rendered);
        } else {
            sendLines(sender, currentSettings.peerOffline(), placeholders);
        }
    }

    private void sendLines(Player player, List<String> lines, Map<String, String> placeholders) {
        for (String line : lines) {
            player.sendMessage(messages.render(line, placeholders));
        }
    }

    private static String sanitize(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    private static boolean requiredBoolean(YamlConfiguration config, String path) {
        if (!config.isBoolean(path)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
        return config.getBoolean(path);
    }

    private static int requiredInt(YamlConfiguration config, String path, int minimum, int maximum) {
        if (!config.isInt(path)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        int value = config.getInt(path);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String requiredString(YamlConfiguration config, String path) {
        if (!config.isString(path)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        String value = config.getString(path, "").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
        return value;
    }

    private static List<String> requiredLines(YamlConfiguration config, String path) {
        if (!config.isList(path)) {
            throw new IllegalArgumentException(path + " must be a YAML list");
        }
        List<String> lines = List.copyOf(config.getStringList(path));
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(path + " must not be empty");
        }
        return lines;
    }

    private record Settings(
            boolean enabled,
            int maxMessageLength,
            String checkedFormat,
            String moderatorFormat,
            List<String> peerOffline,
            List<String> ambiguous,
            List<String> sessionChanged,
            List<String> messageTooLong
    ) {
    }
}
