package com.slyph.clovercheck.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginTasksTest {
    @Test
    void disabledPluginDoesNotScheduleCallbacks() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            PluginTasks.runSync(plugin, mock(Runnable.class));
            bukkit.verifyNoInteractions();
        }
    }

    @Test
    void disableBetweenEnabledCheckAndSchedulingDoesNotThrow() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true, false);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Runnable task = mock(Runnable.class);
        when(scheduler.runTask(plugin, task)).thenThrow(new IllegalPluginAccessException("disabled"));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertDoesNotThrow(() -> PluginTasks.runSync(plugin, task));
            verifyNoInteractions(task);
        }
    }

    @Test
    void unexpectedSchedulingFailureIsNotSuppressed() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Runnable task = mock(Runnable.class);
        when(scheduler.runTask(plugin, task)).thenThrow(new IllegalPluginAccessException("unexpected"));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertThrows(IllegalPluginAccessException.class, () -> PluginTasks.runSync(plugin, task));
        }
    }
}
