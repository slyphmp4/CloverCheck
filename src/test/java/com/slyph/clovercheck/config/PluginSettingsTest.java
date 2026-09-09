package com.slyph.clovercheck.config;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.QuitPolicy;
import com.slyph.clovercheck.model.TimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginSettingsTest {
    @Test
    void rejectsUnsafeDatabasePath() {
        assertThrows(IllegalArgumentException.class, () -> settings(new PluginSettings.DatabaseSettings("../checks.db", 10)));
    }

    @Test
    void rejectsInvalidDuration() {
        assertThrows(IllegalArgumentException.class, () -> new PluginSettings(
                Duration.ZERO, 60, true, "Manual check", "discord.gg/example", Set.of("check"), freeze(),
                QuitPolicy.NOTIFY_ONLY, TimeoutPolicy.MARK_AS_TIMEOUT,
                new PluginSettings.ConfessSettings(true, 30), new PluginSettings.StaffSettings(true),
                new PluginSettings.BossBarSettings(true, "YELLOW", "PROGRESS"),
                new PluginSettings.TitleSettings(true, 10, 60, 10), new PluginSettings.ActionBarSettings(true),
                new PluginSettings.SoundSettings(false, "minecraft:block.note_block.pling", "minecraft:block.note_block.pling", 1.0F, 1.0F),
                new PluginSettings.TeleportSettings(false), new PluginSettings.DatabaseSettings("checks.db", 10),
                Map.of(CheckResult.CLEAN, List.of()), false, "ru_RU"
        ));
    }

    private static PluginSettings settings(PluginSettings.DatabaseSettings database) {
        return new PluginSettings(
                Duration.ofMinutes(15), 60, true, "Manual check", "discord.gg/example", Set.of("check"), freeze(),
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
