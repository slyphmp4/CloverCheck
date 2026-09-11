package com.slyph.clovercheck.config;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.QuitPolicy;
import com.slyph.clovercheck.model.TimeoutPolicy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSettingsTest {
    @Test
    void rejectsUnsafeDatabasePath() {
        assertThrows(IllegalArgumentException.class, () -> settings(new PluginSettings.DatabaseSettings("../checks.db", 10)));
    }

    @Test
    void rejectsInvalidDuration() {
        assertThrows(IllegalArgumentException.class, () -> new PluginSettings(
                Duration.ZERO, 60, true, "Manual check", "discord.gg/example", Set.of("check"),
                new PluginSettings.ConsoleSettings(false), freeze(),
                QuitPolicy.NOTIFY_ONLY, TimeoutPolicy.MARK_AS_TIMEOUT,
                new PluginSettings.ConfessSettings(true, 30), new PluginSettings.StaffSettings(true),
                new PluginSettings.BossBarSettings(true, "YELLOW", "PROGRESS"),
                new PluginSettings.TitleSettings(true, 10, 60, 10), new PluginSettings.ActionBarSettings(true),
                new PluginSettings.SoundSettings(false, "minecraft:block.note_block.pling", "minecraft:block.note_block.pling", 1.0F, 1.0F),
                new PluginSettings.TeleportSettings(false), new PluginSettings.DatabaseSettings("checks.db", 10),
                Map.of(CheckResult.CLEAN, List.of()), false, "ru_RU"
        ));
    }

    @Test
    void consoleStartChecksCanBeDisabled() {
        assertFalse(settings(new PluginSettings.DatabaseSettings("checks.db", 10)).console().allowStartChecks());
    }

    @Test
    void migratesUntouchedV4PunishmentDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("version", 4);
        config.set("quit.policy", "NOTIFY_ONLY");
        config.set("actions.left", List.of());
        config.set("actions.confessed", List.of());

        assertTrue(ConfigService.migrateV4PunishmentDefaults(config));
        assertEquals(5, config.getInt("version"));
        assertEquals("EXECUTE_COMMANDS", config.getString("quit.policy"));
        assertEquals(List.of("tempban %player% 7d Уход с проверки"), config.getStringList("actions.left"));
        assertEquals(List.of("tempban %player% 3d Читы, признался"), config.getStringList("actions.confessed"));
    }

    @Test
    void preservesCustomizedV4PunishmentSettings() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("version", 4);
        config.set("quit.policy", "MARK_AS_LEFT");
        config.set("actions.left", List.of("customban %player%"));
        config.set("actions.confessed", List.of());

        assertFalse(ConfigService.migrateV4PunishmentDefaults(config));
        assertEquals(5, config.getInt("version"));
        assertEquals("MARK_AS_LEFT", config.getString("quit.policy"));
        assertEquals(List.of("customban %player%"), config.getStringList("actions.left"));
        assertTrue(config.getStringList("actions.confessed").isEmpty());
    }

    private static PluginSettings settings(PluginSettings.DatabaseSettings database) {
        return new PluginSettings(
                Duration.ofMinutes(15), 60, true, "Manual check", "discord.gg/example", Set.of("check"),
                new PluginSettings.ConsoleSettings(false), freeze(),
                QuitPolicy.NOTIFY_ONLY, TimeoutPolicy.MARK_AS_TIMEOUT,
                new PluginSettings.ConfessSettings(true, 30), new PluginSettings.StaffSettings(true),
                new PluginSettings.BossBarSettings(true, "YELLOW", "PROGRESS"),
                new PluginSettings.TitleSettings(true, 10, 60, 10), new PluginSettings.ActionBarSettings(true),
                new PluginSettings.SoundSettings(false, "minecraft:block.note_block.pling", "minecraft:block.note_block.pling", 1.0F, 1.0F),
                new PluginSettings.TeleportSettings(false), database, Map.of(), false, "ru_RU"
        );
    }

    private static PluginSettings.FreezeSettings freeze() {
        return new PluginSettings.FreezeSettings(
                true, true, true, true, true, true, true, true, true, true,
                true, true, true, true, true, true, true, true, true
        );
    }
}
