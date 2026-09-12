package com.slyph.clovercheck.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginTasks {
    private PluginTasks() { }

    public static void runSync(JavaPlugin plugin, Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException exception) {
            // Disable can race an asynchronous storage/chat callback.
            if (plugin.isEnabled()) throw exception;
        }
    }
}
