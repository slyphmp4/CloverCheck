package com.slyph.clovercheck.ui;

import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.service.MessageService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class CheckUiService {
    private static final int CHECK_BLINDNESS_DURATION_TICKS = 80;
    private static final int PERSISTENT_TITLE_STAY_TICKS = 40;

    private final ConfigService config;
    private final MessageService messages;
    private final Logger logger;
    private final boolean cardboardRuntime;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, BlindnessSnapshot> blindnessBeforeCheck = new HashMap<>();
    private final Set<UUID> blindnessVerified = new HashSet<>();
    private boolean bossBarSupported = true;
    private boolean bossBarFailureLogged;
    private boolean titleSupported = true;
    private boolean titleFailureLogged;
    private boolean blindnessVisualSupported = true;
    private boolean blindnessFailureLogged;
    private boolean blindnessSnapshotFailureLogged;

    public CheckUiService(ConfigService config, MessageService messages, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.logger = logger;
        this.cardboardRuntime = "Cardboard".equalsIgnoreCase(Bukkit.getServer().getName());
    }

    public void showStart(Player player, Map<String, String> placeholders, float progress) {
        PluginSettings settings = config.settings();
        ensureBlindness(player);
        showOrUpdateBossBar(player, placeholders, progress);
        showStartTitle(player, placeholders);
        playConfiguredSound(player, settings.sounds().startKey());
        updateActionBar(player, placeholders);
    }

    public void showResume(Player player, Map<String, String> placeholders, float progress) {
        ensureBlindness(player);
        showOrUpdateBossBar(player, placeholders, progress);
        showPersistentTitle(player, placeholders);
        updateActionBar(player, placeholders);
    }

    public void update(Player player, Map<String, String> placeholders, float progress) {
        ensureBlindness(player);
        showOrUpdateBossBar(player, placeholders, progress);
        showPersistentTitle(player, placeholders);
        updateActionBar(player, placeholders);
    }

    public void complete(Player player, Map<String, String> placeholders) {
        hide(player);
        restoreBlindness(player);
        clearTitle(player);
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
            restoreBlindness(player);
            clearTitle(player);
            if (config.settings().actionBar().enabled()) {
                player.sendActionBar(Component.empty());
            }
        }
        bossBars.clear();
        blindnessBeforeCheck.clear();
        blindnessVerified.clear();
    }

    private void showOrUpdateBossBar(Player player, Map<String, String> placeholders, float progress) {
        PluginSettings.BossBarSettings settings = config.settings().bossBar();
        if (!settings.enabled() || !bossBarSupported) {
            hide(player);
            return;
        }
        try {
            BossBar.Color color = BossBar.Color.valueOf(settings.color());
            BossBar.Overlay overlay = BossBar.Overlay.valueOf(settings.overlay());
            Component name = messages.component("ui.bossbar.text", placeholders);
            float normalizedProgress = clamp(progress);
            BossBar bossBar = bossBars.get(player.getUniqueId());
            if (bossBar == null) {
                showNewBossBar(player, name, normalizedProgress, color, overlay);
                return;
            }
            if (cardboardRuntime) {
                recreateBossBar(player, bossBar, name, normalizedProgress, color, overlay);
                return;
            }
            bossBar.name(name);
            bossBar.progress(normalizedProgress);
            bossBar.color(color);
            bossBar.overlay(overlay);
        } catch (RuntimeException exception) {
            disableBossBar(exception);
        }
    }

    private void showNewBossBar(
            Player player,
            Component name,
            float progress,
            BossBar.Color color,
            BossBar.Overlay overlay
    ) {
        BossBar created = BossBar.bossBar(name, progress, color, overlay);
        player.showBossBar(created);
        bossBars.put(player.getUniqueId(), created);
    }

    private void recreateBossBar(
            Player player,
            BossBar current,
            Component name,
            float progress,
            BossBar.Color color,
            BossBar.Overlay overlay
    ) {
        player.hideBossBar(current);
        BossBar replacement = BossBar.bossBar(name, progress, color, overlay);
        player.showBossBar(replacement);
        bossBars.put(player.getUniqueId(), replacement);
    }

    private void showStartTitle(Player player, Map<String, String> placeholders) {
        PluginSettings.TitleSettings settings = config.settings().title();
        if (!settings.enabled() || !titleSupported) {
            return;
        }
        try {
            Title.Times times = Title.Times.times(
                    ticks(settings.fadeInTicks()),
                    ticks(settings.stayTicks()),
                    ticks(settings.fadeOutTicks())
            );
            player.showTitle(Title.title(
                    messages.component("ui.title.title", placeholders),
                    messages.component("ui.title.subtitle", placeholders),
                    times
            ));
        } catch (RuntimeException exception) {
            disableTitle(exception);
        }
    }

    private void showPersistentTitle(Player player, Map<String, String> placeholders) {
        PluginSettings.TitleSettings settings = config.settings().title();
        if (!settings.enabled() || !titleSupported) {
            return;
        }
        try {
            Title.Times times = Title.Times.times(
                    Duration.ZERO,
                    ticks(Math.max(PERSISTENT_TITLE_STAY_TICKS, settings.stayTicks())),
                    Duration.ZERO
            );
            player.showTitle(Title.title(
                    messages.component("ui.title.title", placeholders),
                    messages.component("ui.title.subtitle", placeholders),
                    times
            ));
        } catch (RuntimeException exception) {
            disableTitle(exception);
        }
    }

    private void clearTitle(Player player) {
        if (!titleSupported) {
            return;
        }
        try {
            player.clearTitle();
        } catch (RuntimeException exception) {
            disableTitle(exception);
        }
    }

    private void ensureBlindness(Player player) {
        if (!config.settings().freeze().blindness()) {
            restoreBlindness(player);
            return;
        }
        if (!blindnessVisualSupported) {
            return;
        }
        blindnessBeforeCheck.computeIfAbsent(
                player.getUniqueId(),
                ignored -> captureBlindness(player)
        );
        PotionEffect effect = new PotionEffect(
                PotionEffectType.BLINDNESS,
                CHECK_BLINDNESS_DURATION_TICKS,
                0,
                false,
                false,
                false
        );
        try {
            player.sendPotionEffectChange(player, effect);
            markBlindnessVerified(player, effect);
        } catch (RuntimeException | LinkageError exception) {
            disableBlindness(exception);
        }
    }

    private BlindnessSnapshot captureBlindness(Player player) {
        Instant capturedAt = Instant.now();
        try {
            return new BlindnessSnapshot(
                    player.getPotionEffect(PotionEffectType.BLINDNESS),
                    capturedAt,
                    true
            );
        } catch (RuntimeException exception) {
            if (!blindnessSnapshotFailureLogged) {
                blindnessSnapshotFailureLogged = true;
                logger.warning(
                        "Could not read the player's existing Blindness effect; CloverCheck will use client-side Blindness without modifying server potion state ("
                                + exception.getClass().getSimpleName() + ": " + safeMessage(exception) + ")"
                );
            }
            return new BlindnessSnapshot(null, capturedAt, false);
        }
    }

    private void markBlindnessVerified(Player player, PotionEffect effect) {
        if (config.settings().debug() && blindnessVerified.add(player.getUniqueId())) {
            logger.info(
                    "[DEBUG] client blindness sent player=" + player.getName()
                            + " duration=" + effect.getDuration()
                            + " amplifier=" + effect.getAmplifier()
            );
        }
    }

    private void restoreBlindness(Player player) {
        blindnessVerified.remove(player.getUniqueId());
        BlindnessSnapshot snapshot = blindnessBeforeCheck.remove(player.getUniqueId());
        if (snapshot == null || !blindnessVisualSupported) {
            return;
        }
        if (!snapshot.capturedSuccessfully()) {
            return;
        }
        try {
            player.sendPotionEffectChangeRemove(player, PotionEffectType.BLINDNESS);
            PotionEffect original = snapshot.effect();
            if (original == null) {
                return;
            }
            int remaining = remainingDuration(original, snapshot.capturedAt(), Instant.now());
            if (remaining == 0) {
                return;
            }
            player.sendPotionEffectChange(player, new PotionEffect(
                    PotionEffectType.BLINDNESS,
                    remaining,
                    original.getAmplifier(),
                    original.isAmbient(),
                    original.hasParticles(),
                    original.hasIcon()
            ));
        } catch (RuntimeException | LinkageError exception) {
            disableBlindness(exception);
        }
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

    private void disableTitle(RuntimeException exception) {
        titleSupported = false;
        if (titleFailureLogged) {
            return;
        }
        titleFailureLogged = true;
        logger.warning(
                "Adventure Title is unavailable on this server runtime; CloverCheck will continue without Title ("
                        + exception.getClass().getSimpleName() + ": " + safeMessage(exception) + ")"
        );
    }

    private void disableBlindness(Throwable exception) {
        blindnessVisualSupported = false;
        if (blindnessFailureLogged) {
            return;
        }
        blindnessFailureLogged = true;
        logger.warning(
                "Client-side Blindness is unavailable on this server runtime; CloverCheck will continue without it ("
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

    private static int remainingDuration(PotionEffect effect, Instant capturedAt, Instant now) {
        if (effect.isInfinite()) {
            return PotionEffect.INFINITE_DURATION;
        }
        long elapsedTicks = Math.max(0L, Duration.between(capturedAt, now).toMillis() / 50L);
        long remaining = effect.getDuration() - elapsedTicks;
        if (remaining <= 0L) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    private static float clamp(float progress) {
        return Math.max(0.0F, Math.min(1.0F, progress));
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "no message";
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    private record BlindnessSnapshot(PotionEffect effect, Instant capturedAt, boolean capturedSuccessfully) {
    }
}
