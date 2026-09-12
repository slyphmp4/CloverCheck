package com.slyph.clovercheck.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigServiceTest {
    @TempDir Path directory;

    @Test
    void invalidV4ConfigIsNotRewrittenByMigration() throws Exception {
        Path file = directory.resolve("config.yml");
        try (var source = getClass().getResourceAsStream("/config.yml")) {
            Files.copy(source, file);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        yaml.set("version", 4);
        yaml.set("quit.policy", "NOTIFY_ONLY");
        yaml.set("actions.left", List.of());
        yaml.set("actions.confessed", List.of());
        yaml.set("check.duration-seconds", -1);
        yaml.save(file.toFile());
        String before = Files.readString(file);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        assertFalse(new ConfigService(plugin).loadInitial());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void changedDatabaseFileIsDeferredUntilRestart() throws Exception {
        Path file = directory.resolve("config.yml");
        try (var source = getClass().getResourceAsStream("/config.yml")) {
            Files.copy(source, file);
        }
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        ConfigService config = new ConfigService(plugin);
        assertTrue(config.loadInitial());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        yaml.set("database.file", "next.db");
        yaml.set("database.history-limit", 20);
        yaml.save(file.toFile());
        var prepared = config.prepareReload();
        assertTrue(prepared.databaseRestartRequired());
        assertEquals(10, config.settings().database().historyLimit());
        prepared.apply().run();
        assertEquals("checks.db", config.settings().database().file());
        assertEquals(20, config.settings().database().historyLimit());
    }
}
