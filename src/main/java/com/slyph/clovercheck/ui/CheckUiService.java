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
    private static final int CHECK_BLINDNESS_REFRESH_THRESHOLD_TICKS = 40;
    private static final int PERSISTENT_TITLE_STAY_TICKS = 40;

    private final ConfigService config;
    private final MessageService messages;
    private final Logger logger;
    private final boolean cardboardRuntime;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, BlindnessSnapshot> blindnessBeforeCheck = new HashMap<>();
    private final Set<UUID> titlePlayers = new HashSet<>();
    private final Set<UUID> actionBarPlayers = new HashSet<>();
    private boolean bossBarSupported = true;
    private boolean bossBarFailureLogged;
    private boolean titleSupported = true;
    private boolean titleFailureLogged;
    private boolean blindnessSupported = true;
    private boolean blindnessFailureLogged;
    private boolean blindnessSnapshotFailureLogged;
    private boolean blindnessApplicationFailureLogged;

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
        clear(player);
        playConfiguredSound(player, config.settings().sounds().completeKey());
    }

    public void clear(Player player) {
        hide(player);
        restoreBlindness(player);
        clearTitle(player);
        clearActionBar(player);
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
            clear(player);
        }
        bossBars.clear();
        blindnessBeforeCheck.clear();
        titlePlayers.clear();
        actionBarPlayers.clear();
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
            titlePlayers.add(player.getUniqueId());
        } catch (RuntimeException exception) {
            disableTitle(exception);
        }
    }

    private void showPersistentTitle(Player player, Map<String, String> placeholders) {
        PluginSettings.TitleSettings settings = config.settings().title();
        if (!settings.enabled() || !titleSupported) {
            clearTitle(player);
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
            titlePlayers.add(player.getUniqueId());
        } catch (RuntimeException exception) {
            disableTitle(exception);
        }
    }

    private void clearTitle(Player player) {
        if (!titlePlayers.remove(player.getUniqueId())) {
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
        if (!blindnessSupported) {
            return;
        }
        if (cardboardRuntime) {
            ensureCardboardBlindness(player);
            return;
        }
        ensureServerBlindness(player);
    }

    private void ensureCardboardBlindness(Player player) {
        blindnessBeforeCheck.computeIfAbsent(player.getUniqueId(), ignored -> captureBlindness(player));
        try {
            player.sendPotionEffectChange(player, checkBlindness());
        } catch (RuntimeException | LinkageError exception) {
            disableBlindness(exception);
        }
    }

    private void ensureServerBlindness(Player player) {
        try {
            blindnessBeforeCheck.computeIfAbsent(player.getUniqueId(), ignored -> captureBlindness(player));
            PotionEffect current = player.getPotionEffect(PotionEffectType.BLINDNESS);
            if (current != null && (current.isInfinite() || current.getDuration() > CHECK_BLINDNESS_REFRESH_THRESHOLD_TICKS)) {
                return;
            }
            boolean applied = player.addPotionEffect(checkBlindness());
            if (!applied && !blindnessApplicationFailureLogged) {
                blindnessApplicationFailureLogged = true;
                logger.warning("CloverCheck could not apply Blindness through the server PotionEffect API.");
            }
        } catch (RuntimeException exception) {
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
                        "Could not read the player's existing Blindness effect; CloverCheck will continue without exact Blindness restoration ("
                                + exception.getClass().getSimpleName() + ": " + safeMessage(exception) + ")"
                );
            }
            return new BlindnessSnapshot(null, capturedAt, false);
        }
    }

    private PotionEffect checkBlindness() {
        return new PotionEffect(
                PotionEffectType.BLINDNESS,
                CHECK_BLINDNESS_DURATION_TICKS,
                0,
                false,
                false,
                false
        );
    }

    private void restoreBlindness(Player player) {
        BlindnessSnapshot snapshot = blindnessBeforeCheck.remove(player.getUniqueId());
        if (snapshot == null || !blindnessSupported) {
            return;
        }
        if (cardboardRuntime) {
            restoreCardboardBlindness(player, snapshot);
            return;
        }
        restoreServerBlindness(player, snapshot);
    }

    private void restoreCardboardBlindness(Player player, BlindnessSnapshot snapshot) {
        try {
            player.sendPotionEffectChangeRemove(player, PotionEffectType.BLINDNESS);
            if (!snapshot.capturedSuccessfully() || snapshot.effect() == null) {
                return;
            }
            int remaining = remainingDuration(snapshot.effect(), snapshot.capturedAt(), Instant.now());
            if (remaining == 0) {
                return;
            }
            player.sendPotionEffectChange(player, copyWithDuration(snapshot.effect(), remaining));
        } catch (RuntimeException | LinkageError exception) {
            disableBlindness(exception);
        }
    }

    private void restoreServerBlindness(Player player, BlindnessSnapshot snapshot) {
        try {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            if (!snapshot.capturedSuccessfully() || snapshot.effect() == null) {
                return;
            }
            int remaining = remainingDuration(snapshot.effect(), snapshot.capturedAt(), Instant.now());
            if (remaining == 0) {
                return;
            }
            player.addPotionEffect(copyWithDuration(snapshot.effect(), remaining));
        } catch (RuntimeException exception) {
            disableBlindness(exception);
        }
    }

    private PotionEffect copyWithDuration(PotionEffect effect, int duration) {
        return new PotionEffect(
                PotionEffectType.BLINDNESS,
                duration,
                effect.getAmplifier(),
                effect.isAmbient(),
                effect.hasParticles(),
                effect.hasIcon()
        );
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
        blindnessSupported = false;
        if (blindnessFailureLogged) {
            return;
        }
        blindnessFailureLogged = true;
        logger.warning(
                "Blindness enforcement is unavailable on this server runtime; CloverCheck will continue without it ("
                        + exception.getClass().getSimpleName() + ": " + safeMessage(exception) + ")"
        );
    }

    private void updateActionBar(Player player, Map<String, String> placeholders) {
        if (config.settings().actionBar().enabled()) {
            player.sendActionBar(messages.component("ui.actionbar.text", placeholders));
            actionBarPlayers.add(player.getUniqueId());
        } else {
            clearActionBar(player);
        }
    }

    private void clearActionBar(Player player) {
        if (actionBarPlayers.remove(player.getUniqueId())) {
            player.sendActionBar(Component.empty());
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
