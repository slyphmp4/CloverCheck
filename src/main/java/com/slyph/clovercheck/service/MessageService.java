package com.slyph.clovercheck.service;

import com.slyph.clovercheck.util.LegacyMiniMessageCompat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class MessageService {
    private final File file;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile YamlConfiguration messages = new YamlConfiguration();

    public MessageService(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        this.logger = plugin.getLogger();
    }

    public boolean reload() {
        try {
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.load(file);
            if (!loaded.isConfigurationSection("messages") || !loaded.isConfigurationSection("ui")) {
                throw new InvalidConfigurationException("messages.yml must contain messages and ui sections");
            }
            messages = loaded;
            return true;
        } catch (IOException | InvalidConfigurationException exception) {
            logger.severe("Failed to load messages.yml: " + exception.getMessage());
            return false;
        }
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        String path = "messages." + key;
        YamlConfiguration current = messages;
        if (current.isList(path)) {
            for (String line : current.getStringList(path)) {
                sender.sendMessage(render(line, placeholders));
            }
            return;
        }
        String single = current.getString(path);
        if (single == null) {
            logger.warning("Missing message key: " + path);
            return;
        }
        sender.sendMessage(render(single, placeholders));
    }

    public Component component(String path, Map<String, String> placeholders) {
        String template = messages.getString(path);
        if (template == null) {
            logger.warning("Missing component key: " + path);
            return Component.empty();
        }
        return render(template, placeholders);
    }

    public List<String> rawLines(String path) {
        return List.copyOf(messages.getStringList(path));
    }

    public Component render(String template, Map<String, String> placeholders) {
        TagResolver.Builder resolver = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (entry.getKey().matches("[a-z0-9_]+")) {
                resolver.resolver(Placeholder.unparsed(entry.getKey(), entry.getValue() == null ? "" : entry.getValue()));
            }
        }
        return miniMessage.deserialize(LegacyMiniMessageCompat.convert(template), resolver.build());
    }
}
