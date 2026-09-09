package com.slyph.clovercheck.listener;

import com.slyph.clovercheck.chat.CheckChatService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

public final class LegacyCheckChatBridge {
    private final JavaPlugin plugin;
    private final CheckChatService chat;
    private Method getPlayerMethod;
    private Method getMessageMethod;
    private Method setCancelledMethod;

    public LegacyCheckChatBridge(JavaPlugin plugin, CheckChatService chat) {
        this.plugin = plugin;
        this.chat = chat;
    }

    @SuppressWarnings("unchecked")
    public boolean register() {
        try {
            Class<?> eventClass = Class.forName("org.bukkit.event.player.AsyncPlayerChatEvent", false, plugin.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(eventClass)) {
                return false;
            }
            getPlayerMethod = eventClass.getMethod("getPlayer");
            getMessageMethod = eventClass.getMethod("getMessage");
            setCancelledMethod = eventClass.getMethod("setCancelled", boolean.class);

            Listener listener = new Listener() {
            };
            EventExecutor executor = this::execute;
            plugin.getServer().getPluginManager().registerEvent(
                    (Class<? extends Event>) eventClass,
                    listener,
                    EventPriority.LOWEST,
                    executor,
                    plugin,
                    true
            );
            return true;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register CloverCheck Cardboard chat bridge.", exception);
            return false;
        }
    }

    private void execute(Listener listener, Event event) {
        try {
            Player player = (Player) getPlayerMethod.invoke(event);
            String message = (String) getMessageMethod.invoke(event);
            if (player != null && message != null && chat.intercept(player.getUniqueId(), Component.text(message))) {
                setCancelledMethod.invoke(event, true);
            }
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to process CloverCheck Cardboard chat event.", exception);
        }
    }
}
