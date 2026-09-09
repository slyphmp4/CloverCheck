package com.slyph.clovercheck.ui;

import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.service.MessageService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class CheckUiService {
    private static final int CHECK_BLINDNESS_DURATION_TICKS = 60;
    private static final int CHECK_BLINDNESS_REFRESH_THRESHOLD_TICKS = 40;
    private static final int CARDBOARD_BLINDNESS_SECONDS = 2;
    private static final int PERSISTENT_TITLE_STAY_TICKS = 40;

    private final ConfigService config;
    private final MessageService messages;
    private final Logger logger;
    private final boolean cardboardRuntime;
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final Map<UUID, BossBar> adventureBossBars = new HashMap<>();
    private final Map<UUID, org.bukkit.boss.BossBar> bukkitBossBars = new HashMap<>();
    private final Map<UUID, BlindnessSnapshot> blindnessBeforeCheck = new HashMap<>();
    private boolean bossBarSupported = true;
    private boolean bossBarFailureLogged;
    private boolean titleSupported = true;
    private boolean titleFailureLogged;
    private boolean blindnessSupported = true;
    private boolean blindnessFailureLogged;

    public CheckUiService(ConfigService config, MessageService messages, Logger logger, boolean cardboardRuntime) {
        this.config = config;
        this.messages = messages;
        this.logger = logger;
        this.cardboardRuntime = cardboardRuntime;
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
        if (cardboardRuntime) {
            hideBukkitBossBar(player);
        } else {
            hideAdventureBossBar(player);
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
        adventureBossBars.clear();
        for (org.bukkit.boss.BossBar bossBar : bukkitBossBars.values()) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
        bukkitBossBars.clear();
        blindnessBeforeCheck.clear();
    }

    private void showOrUpdateBossBar(Player player, Map<String, String> placeholders, float progress) {
        PluginSettings.BossBarSettings settings = config.settings().bossBar();
        if (!settings.enabled() || !bossBarSupported) {
            hide(player);
            return;
        }
        if (cardboardRuntime) {
            showOrUpdateBukkitBossBar(player, placeholders, progress, settings);
        } else {
            showOrUpdateAdventureBossBar(player, placeholders, progress, settings);
        }
    }

    private void showOrUpdateAdventureBossBar(
            Player player,
            Map<String, String> placeholders,
            float progress,
            PluginSettings.BossBarSettings settings
    ) {
        BossBar.Color color = BossBar.Color.valueOf(settings.color());
        BossBar.Overlay overlay = BossBar.Overlay.valueOf(settings.overlay());
        BossBar bossBar = adventureBossBars.get(player.getUniqueId());
        if (bossBar == null) {
            BossBar created = BossBar.bossBar(
                    messages.component("ui.bossbar.text", placeholders),
                    clamp(progress),
                    color,
                    overlay
            );
            try {
                player.showBossBar(created);
                adventureBossBars.put(player.getUniqueId(), created);
            } catch (RuntimeException exception) {
                disableBossBar("Adventure BossBar", exception);
            }
            return;
        }
        bossBar.name(messages.component("ui.bossbar.text", placeholders));
        bossBar.progress(clamp(progress));
        bossBar.color(color);
        bossBar.overlay(overlay);
    }

    private void showOrUpdateBukkitBossBar(
            Player player,
            Map<String, String> placeholders,
            float progress,
            PluginSettings.BossBarSettings settings
    ) {
        try {
            String title = plainText.serialize(messages.component("ui.bossbar.text", placeholders));
            BarColor color = BarColor.valueOf(settings.color());
            BarStyle style = bukkitStyle(settings.overlay());
            org.bukkit.boss.BossBar bossBar = bukkitBossBars.get(player.getUniqueId());
            if (bossBar == null) {
                bossBar = Bukkit.createBossBar(title, color, style);
                bossBar.setProgress(clamp(progress));
                bossBar.addPlayer(player);
                bossBar.setVisible(true);
                bukkitBossBars.put(player.getUniqueId(), bossBar);
                return;
            }
            bossBar.setTitle(title);
            bossBar.setProgress(clamp(progress));
            bossBar.setColor(color);
            bossBar.setStyle(style);
            if (!bossBar.getPlayers().contains(player)) {
                bossBar.addPlayer(player);
            }
            bossBar.setVisible(true);
        } catch (RuntimeException exception) {
            disableBossBar("Bukkit/Cardboard BossBar", exception);
            hideBukkitBossBar(player);
        }
    }

    private void hideAdventureBossBar(Player player) {
        BossBar bossBar = adventureBossBars.remove(player.getUniqueId());
        if (bossBar == null) {
            return;
        }
        try {
            player.hideBossBar(bossBar);
        } catch (RuntimeException exception) {
            disableBossBar("Adventure BossBar", exception);
        }
    }

    private void hideBukkitBossBar(Player player) {
        org.bukkit.boss.BossBar bossBar = bukkitBossBars.remove(player.getUniqueId());
        if (bossBar == null) {
            return;
        }
        try {
            bossBar.removeAll();
            bossBar.setVisible(false);
        } catch (RuntimeException exception) {
            disableBossBar("Bukkit/Cardboard BossBar", exception);
        }
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
        if (!blindnessSupported) {
            return;
        }
        if (cardboardRuntime) {
            ensureCardboardBlindness(player);
        } else {
            ensureBukkitBlindness(player);
        }
    }

    private void ensureBukkitBlindness(Player player) {
        try {
            blindnessBeforeCheck.computeIfAbsent(
                    player.getUniqueId(),
                    ignored -> new BlindnessSnapshot(player.getPotionEffect(PotionEffectType.BLINDNESS), Instant.now())
            );
            PotionEffect current = player.getPotionEffect(PotionEffectType.BLINDNESS);
            if (current != null && (current.isInfinite() || current.getDuration() > CHECK_BLINDNESS_REFRESH_THRESHOLD_TICKS)) {
                return;
            }
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS,
                    CHECK_BLINDNESS_DURATION_TICKS,
                    0,
                    false,
                    false,
                    false
            ));
        } catch (RuntimeException exception) {
            disableBlindness(exception);
        }
    }

    private void ensureCardboardBlindness(Player player) {
        String playerName = player.getName();
        if (!playerName.matches("[A-Za-z0-9_]{1,16}")) {
            disableBlindness(new IllegalArgumentException("unsafe player name for vanilla command fallback"));
            return;
        }
        try {
            String command = "minecraft:effect give " + playerName + " minecraft:blindness "
                    + CARDBOARD_BLINDNESS_SECONDS + " 0 true";
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                disableBlindness(new IllegalStateException("vanilla effect command was rejected"));
            }
        } catch (RuntimeException exception) {
            disableBlindness(exception);
        }
    }

    private void restoreBlindness(Player player) {
        if (cardboardRuntime) {
            return;
        }
        BlindnessSnapshot snapshot = blindnessBeforeCheck.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        try {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            PotionEffect original = snapshot.effect();
            if (original == null) {
                return;
            }
            int remaining = remainingDuration(original, snapshot.capturedAt(), Instant.now());
            if (remaining == 0) {
                return;
            }
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS,
                    remaining,
                    original.getAmplifier(),
                    original.isAmbient(),
                    original.hasParticles(),
                    original.hasIcon()
            ));
        } catch (RuntimeException exception) {
            disableBlindness(exception);
        }
    }

    private void disableBossBar(String implementation, RuntimeException exception) {
        bossBarSupported = false;
        adventureBossBars.clear();
        if (bossBarFailureLogged) {
            return;
        }
        bossBarFailureLogged = true;
        logger.warning(
                implementation + " is unavailable on this server runtime; CloverCheck will continue without BossBar ("
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

    private void disableBlindness(RuntimeException exception) {
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

    private static BarStyle bukkitStyle(String overlay) {
        return switch (overlay) {
            case "NOTCHED_6" -> BarStyle.SEGMENTED_6;
            case "NOTCHED_10" -> BarStyle.SEGMENTED_10;
            case "NOTCHED_12" -> BarStyle.SEGMENTED_12;
            case "NOTCHED_20" -> BarStyle.SEGMENTED_20;
            default -> BarStyle.SOLID;
        };
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

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "no message";
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    private record BlindnessSnapshot(PotionEffect effect, Instant capturedAt) {
    }
}
