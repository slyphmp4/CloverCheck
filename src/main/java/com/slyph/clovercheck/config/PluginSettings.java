package com.slyph.clovercheck.config;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.QuitPolicy;
import com.slyph.clovercheck.model.TimeoutPolicy;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PluginSettings(
        Duration checkDuration,
        int reminderIntervalSeconds,
        boolean oneActivePerModerator,
        String defaultReason,
        String contact,
        Set<String> commandWhitelist,
        FreezeSettings freeze,
        QuitPolicy quitPolicy,
        TimeoutPolicy timeoutPolicy,
        ConfessSettings confess,
        StaffSettings staff,
        BossBarSettings bossBar,
        TitleSettings title,
        ActionBarSettings actionBar,
        SoundSettings sounds,
        TeleportSettings teleport,
        DatabaseSettings database,
        Map<CheckResult, List<String>> actions,
        boolean debug,
        String locale
) {
    public static final int MAX_REASON_LENGTH = 256;
    public static final int MAX_COMMENT_LENGTH = 512;

    public PluginSettings {
        Objects.requireNonNull(checkDuration, "checkDuration");
        Objects.requireNonNull(defaultReason, "defaultReason");
        Objects.requireNonNull(contact, "contact");
        Objects.requireNonNull(commandWhitelist, "commandWhitelist");
        Objects.requireNonNull(freeze, "freeze");
        Objects.requireNonNull(quitPolicy, "quitPolicy");
        Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
        Objects.requireNonNull(confess, "confess");
        Objects.requireNonNull(staff, "staff");
        Objects.requireNonNull(bossBar, "bossBar");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(actionBar, "actionBar");
        Objects.requireNonNull(sounds, "sounds");
        Objects.requireNonNull(teleport, "teleport");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(locale, "locale");
        if (checkDuration.isZero() || checkDuration.isNegative() || checkDuration.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("check.duration-seconds must be between 1 and 86400");
        }
        if (reminderIntervalSeconds < 0) {
            throw new IllegalArgumentException("check.reminder-interval-seconds must be >= 0");
        }
        if (defaultReason.isBlank() || defaultReason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("check.default-reason must contain 1-256 characters");
        }
        if (contact.isBlank()) {
            throw new IllegalArgumentException("check.contact must not be blank");
        }
        if (commandWhitelist.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("commands.whitelist contains a blank command");
        }
        commandWhitelist = Set.copyOf(commandWhitelist);
        EnumMap<CheckResult, List<String>> actionCopy = new EnumMap<>(CheckResult.class);
        for (CheckResult result : CheckResult.values()) {
            actionCopy.put(result, List.copyOf(actions.getOrDefault(result, List.of())));
        }
        actions = Map.copyOf(actionCopy);
    }

    public PluginSettings withDatabase(DatabaseSettings databaseSettings) {
        return new PluginSettings(
                checkDuration, reminderIntervalSeconds, oneActivePerModerator, defaultReason, contact,
                commandWhitelist, freeze, quitPolicy, timeoutPolicy, confess, staff, bossBar, title,
                actionBar, sounds, teleport, databaseSettings, actions, debug, locale
        );
    }

    public List<String> actionCommands(CheckResult result) {
        return actions.getOrDefault(result, List.of());
    }

    public record FreezeSettings(
            boolean movement,
            boolean flight,
            boolean teleport,
            boolean blockBreak,
            boolean blockPlace,
            boolean blockInteract,
            boolean entityInteract,
            boolean itemUse,
            boolean attack,
            boolean damage,
            boolean hunger,
            boolean itemDrop,
            boolean itemPickup,
            boolean inventory,
            boolean heldSlot,
            boolean swapHand,
            boolean vehicle,
            boolean commands
    ) {
    }

    public record ConfessSettings(boolean enabled, int confirmationSeconds) {
        public ConfessSettings {
            if (confirmationSeconds < 5 || confirmationSeconds > 300) {
                throw new IllegalArgumentException("confess.confirmation-seconds must be between 5 and 300");
            }
        }
    }

    public record StaffSettings(boolean notifications) {
    }

    public record BossBarSettings(boolean enabled, String color, String overlay) {
        private static final Set<String> COLORS = Set.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
        private static final Set<String> OVERLAYS = Set.of("PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20");

        public BossBarSettings {
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(overlay, "overlay");
            color = color.toUpperCase(java.util.Locale.ROOT);
            overlay = overlay.toUpperCase(java.util.Locale.ROOT);
            if (!COLORS.contains(color)) {
                throw new IllegalArgumentException("ui.bossbar.color is invalid: " + color);
            }
            if (!OVERLAYS.contains(overlay)) {
                throw new IllegalArgumentException("ui.bossbar.overlay is invalid: " + overlay);
            }
        }
    }

    public record TitleSettings(boolean enabled, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        public TitleSettings {
            if (fadeInTicks < 0 || stayTicks < 0 || fadeOutTicks < 0) {
                throw new IllegalArgumentException("ui.title timings must be >= 0");
            }
        }
    }

    public record ActionBarSettings(boolean enabled) {
    }

    public record SoundSettings(boolean enabled, String startKey, String completeKey, float volume, float pitch) {
        public SoundSettings {
            Objects.requireNonNull(startKey, "startKey");
            Objects.requireNonNull(completeKey, "completeKey");
            if (!validKey(startKey) || !validKey(completeKey)) {
                throw new IllegalArgumentException("ui.sounds keys must be namespaced Minecraft keys");
            }
            if (!Float.isFinite(volume) || volume < 0.0F || !Float.isFinite(pitch) || pitch <= 0.0F) {
                throw new IllegalArgumentException("ui.sounds volume/pitch are invalid");
            }
        }

        private static boolean validKey(String value) {
            return value.matches("[a-z0-9._-]+:[a-z0-9/._-]+");
        }
    }

    public record TeleportSettings(boolean moderatorToPlayerEnabled) {
    }

    public record DatabaseSettings(String file, int historyLimit) {
        public DatabaseSettings {
            Objects.requireNonNull(file, "file");
            if (file.isBlank() || file.contains("..") || file.contains("/") || file.contains("\\")) {
                throw new IllegalArgumentException("database.file must be a file name inside the plugin directory");
            }
            if (historyLimit < 1 || historyLimit > 100) {
                throw new IllegalArgumentException("database.history-limit must be between 1 and 100");
            }
        }
    }
}
