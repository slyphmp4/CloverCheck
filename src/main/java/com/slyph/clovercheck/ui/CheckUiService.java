package com.slyph.clovercheck.ui;

import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.service.MessageService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class CheckUiService {
    private final ConfigService config;
    private final MessageService messages;
    private final Logger logger;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private boolean bossBarSupported = true;
    private boolean bossBarFailureLogged;

    public CheckUiService(ConfigService config, MessageService messages, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.logger = logger;
    }

    public void showStart(Player player, Map<String, String> placeholders, float progress) {
        PluginSettings settings = config.settings();
        showOrUpdateBossBar(player, placeholders, progress);
        if (settings.title().enabled()) {
            Title.Times times = Title.Times.times(
                    ticks(settings.title().fadeInTicks()),
                    ticks(settings.title().stayTicks()),
                    ticks(settings.title().fadeOutTicks())
            );
            player.showTitle(Title.title(
                    messages.component("ui.title.title", placeholders),
                    messages.component("ui.title.subtitle", placeholders),
                    times
            ));
        }
        playConfiguredSound(player, settings.sounds().startKey());
        updateActionBar(player, placeholders);
    }

    public void showResume(Player player, Map<String, String> placeholders, float progress) {
        showOrUpdateBossBar(player, placeholders, progress);
        updateActionBar(player, placeholders);
    }

    public void update(Player player, Map<String, String> placeholders, float progress) {
        showOrUpdateBossBar(player, placeholders, progress);
        updateActionBar(player, placeholders);
    }

    public void complete(Player player, Map<String, String> placeholders) {
        hide(player);
        playConfiguredSound(player, config.settings().sounds().completeKey());
        if (config.settings().actionBar().enabled()) {
            player.sendActionBar(Component.empty());
        }
    }

    public void hide(Player player) {
        BossBar bossBar = bossBars.remove(player.getUniqueId());
        if (bossBar == null) {
            return;
        }
        try {
            player.hideBossBar(bossBar);
        } catch (RuntimeException exception) {
            disableBossBar(exception);
        }
    }

    public void hideAll(Iterable<? extends Player> players) {
        for (Player player : players) {
            hide(player);
        }
        bossBars.clear();
    }

    private void showOrUpdateBossBar(Player player, Map<String, String> placeholders, float progress) {
        PluginSettings.BossBarSettings settings = config.settings().bossBar();
        if (!settings.enabled() || !bossBarSupported) {
            hide(player);
            return;
        }
        BossBar.Color color = BossBar.Color.valueOf(settings.color());
        BossBar.Overlay overlay = BossBar.Overlay.valueOf(settings.overlay());
        BossBar bossBar = bossBars.get(player.getUniqueId());
        if (bossBar == null) {
            BossBar created = BossBar.bossBar(
                    messages.component("ui.bossbar.text", placeholders),
                    clamp(progress),
                    color,
                    overlay
            );
            try {
                player.showBossBar(created);
                bossBars.put(player.getUniqueId(), created);
            } catch (RuntimeException exception) {
                disableBossBar(exception);
            }
            return;
        }
        bossBar.name(messages.component("ui.bossbar.text", placeholders));
        bossBar.progress(clamp(progress));
        bossBar.color(color);
        bossBar.overlay(overlay);
    }

    private void disableBossBar(RuntimeException exception) {
        bossBarSupported = false;
        bossBars.clear();
        if (bossBarFailureLogged) {
            return;
        }
        bossBarFailureLogged = true;
        logger.warning(
                "Adventure BossBar is unavailable on this server runtime; CloverCheck will continue without BossBar ("
                        + exception.getClass().getSimpleName() + ": " + safeMessage(exception) + ")"
        );
    }

    private void updateActionBar(Player player, Map<String, String> placeholders) {
        if (config.settings().actionBar().enabled()) {
            player.sendActionBar(messages.component("ui.actionbar.text", placeholders));
        }
    }

    private void playConfiguredSound(Player player, String key) {
        PluginSettings.SoundSettings settings = config.settings().sounds();
        if (!settings.enabled()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, settings.volume(), settings.pitch()));
        } catch (IllegalArgumentException exception) {
            logger.warning("Invalid CloverCheck sound key: " + key);
        }
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    private static float clamp(float progress) {
        return Math.max(0.0F, Math.min(1.0F, progress));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "no message";
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }
}
