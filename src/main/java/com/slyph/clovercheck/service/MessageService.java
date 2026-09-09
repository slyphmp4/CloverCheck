package com.slyph.clovercheck.service;

import com.slyph.clovercheck.CloverCheckPlugin;
import com.slyph.clovercheck.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class MessageService {
    private final CloverCheckPlugin plugin;
    private final File messagesFile;
    private final File hoversFile;
    private YamlConfiguration messages = new YamlConfiguration();
    private YamlConfiguration hovers = new YamlConfiguration();

    public MessageService(CloverCheckPlugin plugin) {
        this.plugin = plugin;
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        this.hoversFile = new File(plugin.getDataFolder(), "hovers.yml");
    }

    public boolean reload() {
        try {
            YamlConfiguration loadedMessages = new YamlConfiguration();
            loadedMessages.load(messagesFile);
            if (!loadedMessages.isConfigurationSection("messages")) {
                plugin.getLogger().severe("messages.yml does not contain the messages section.");
                return false;
            }

            YamlConfiguration loadedHovers = new YamlConfiguration();
            loadedHovers.load(hoversFile);
            messages = loadedMessages;
            hovers = loadedHovers;
            return true;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Failed to load message files: " + exception.getMessage());
            return false;
        }
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        for (String line : lines("messages." + key)) {
            sender.sendMessage(render(line, placeholders));
        }
    }

    public void sendInteractive(CommandSender sender, String key, String hoverKey, Map<String, String> placeholders) {
        Component hover = hoverComponent(hoverKey, placeholders);
        String clickCommand = replace(hovers.getString(hoverKey + ".click-command", ""), placeholders);

        for (String line : lines("messages." + key)) {
            Component component = render(line, placeholders);
            if (!hover.equals(Component.empty())) {
                component = component.hoverEvent(HoverEvent.showText(hover));
            }
            if (!clickCommand.isBlank()) {
                component = component.clickEvent(ClickEvent.runCommand(clickCommand));
            }
            sender.sendMessage(component);
        }
    }

    public Component render(String input, Map<String, String> placeholders) {
        return ColorUtil.deserialize(replace(input, placeholders));
    }

    private Component hoverComponent(String key, Map<String, String> placeholders) {
        List<String> lines = hovers.getStringList(key + ".lines");
        if (lines.isEmpty()) {
            return Component.empty();
        }

        Component result = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result = result.append(Component.newline());
            }
            result = result.append(render(lines.get(index), placeholders));
        }
        return result;
    }

    private List<String> lines(String path) {
        if (messages.isList(path)) {
            return messages.getStringList(path);
        }
        String single = messages.getString(path);
        if (single == null) {
            plugin.getLogger().warning("Missing message key: " + path);
            return List.of();
        }
        List<String> result = new ArrayList<>(1);
        result.add(single);
        return result;
    }

    private String replace(String input, Map<String, String> placeholders) {
        String result = input == null ? "" : input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace('%' + entry.getKey() + '%', entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }
}
