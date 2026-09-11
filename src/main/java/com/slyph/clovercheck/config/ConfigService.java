package com.slyph.clovercheck.config;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.QuitPolicy;
import com.slyph.clovercheck.model.TimeoutPolicy;
import com.slyph.clovercheck.util.CommandNormalizer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class ConfigService {
    private static final int CURRENT_CONFIG_VERSION = 5;
    private static final String DEFAULT_LEFT_ACTION = "tempban %player% 7d Уход с проверки";
    private static final String DEFAULT_CONFESSED_ACTION = "tempban %player% 3d Читы, признался";

    public record ReloadResult(boolean success, boolean databaseRestartRequired, String error) {
    }

    private final File file;
    private final Logger logger;
    private volatile PluginSettings settings;

    public ConfigService(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "config.yml");
        this.logger = plugin.getLogger();
    }

    public boolean loadInitial() {
        try {
            settings = readSettings();
            return true;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            logger.severe("Invalid CloverCheck config: " + exception.getMessage());
            return false;
        }
    }

    public ReloadResult reload() {
        PluginSettings current = settings;
        try {
            PluginSettings loaded = readSettings();
            boolean databaseRestartRequired = current != null
                    && !current.database().file().equals(loaded.database().file());
            if (databaseRestartRequired) {
                loaded = loaded.withDatabase(new PluginSettings.DatabaseSettings(
                        current.database().file(),
                        loaded.database().historyLimit()
                ));
            }
            settings = loaded;
            return new ReloadResult(true, databaseRestartRequired, "");
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            logger.severe("Failed to reload CloverCheck config: " + exception.getMessage());
            return new ReloadResult(false, false, exception.getMessage());
        }
    }

    public PluginSettings settings() {
        PluginSettings current = settings;
        if (current == null) {
            throw new IllegalStateException("CloverCheck settings are not loaded");
        }
        return current;
    }

    private PluginSettings readSettings() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        migrateConfig(config);
        Set<String> whitelist = readWhitelist(config);
        PluginSettings.FreezeSettings freeze = new PluginSettings.FreezeSettings(
                requiredBoolean(config, "freeze.movement"),
                optionalBoolean(config, "freeze.blindness", true),
                requiredBoolean(config, "freeze.flight"),
                requiredBoolean(config, "freeze.teleport"),
                requiredBoolean(config, "freeze.block-break"),
                requiredBoolean(config, "freeze.block-place"),
                requiredBoolean(config, "freeze.block-interact"),
                requiredBoolean(config, "freeze.entity-interact"),
                requiredBoolean(config, "freeze.item-use"),
                requiredBoolean(config, "freeze.attack"),
                requiredBoolean(config, "freeze.damage"),
                requiredBoolean(config, "freeze.hunger"),
                requiredBoolean(config, "freeze.item-drop"),
                requiredBoolean(config, "freeze.item-pickup"),
                requiredBoolean(config, "freeze.inventory"),
                requiredBoolean(config, "freeze.held-slot"),
                requiredBoolean(config, "freeze.swap-hand"),
                requiredBoolean(config, "freeze.vehicle"),
                requiredBoolean(config, "freeze.commands")
        );
        Map<CheckResult, List<String>> actions = new EnumMap<>(CheckResult.class);
        for (CheckResult result : CheckResult.values()) {
            String key = "actions." + result.name().toLowerCase(Locale.ROOT).replace('_', '-');
            if (config.contains(key) && !config.isList(key)) {
                throw new IllegalArgumentException(key + " must be a YAML list");
            }
            actions.put(result, List.copyOf(config.getStringList(key)));
        }
        return new PluginSettings(
                Duration.ofSeconds(requiredInt(config, "check.duration-seconds")),
                requiredInt(config, "check.reminder-interval-seconds"),
                requiredBoolean(config, "check.one-active-per-moderator"),
                requiredString(config, "check.default-reason"),
                requiredString(config, "check.contact"),
                whitelist,
                new PluginSettings.ConsoleSettings(optionalBoolean(config, "console.allow-start-checks", false)),
                freeze,
                enumValue(config, "quit.policy", QuitPolicy.class),
                enumValue(config, "timeout.policy", TimeoutPolicy.class),
                new PluginSettings.ConfessSettings(requiredBoolean(config, "confess.enabled"), requiredInt(config, "confess.confirmation-seconds")),
                new PluginSettings.StaffSettings(requiredBoolean(config, "staff.notifications")),
                new PluginSettings.BossBarSettings(requiredBoolean(config, "ui.bossbar.enabled"), requiredString(config, "ui.bossbar.color"), requiredString(config, "ui.bossbar.overlay")),
                new PluginSettings.TitleSettings(requiredBoolean(config, "ui.title.enabled"), requiredInt(config, "ui.title.fade-in-ticks"), requiredInt(config, "ui.title.stay-ticks"), requiredInt(config, "ui.title.fade-out-ticks")),
                new PluginSettings.ActionBarSettings(requiredBoolean(config, "ui.actionbar.enabled")),
                new PluginSettings.SoundSettings(requiredBoolean(config, "ui.sounds.enabled"), requiredString(config, "ui.sounds.start"), requiredString(config, "ui.sounds.complete"), (float) requiredDouble(config, "ui.sounds.volume"), (float) requiredDouble(config, "ui.sounds.pitch")),
                new PluginSettings.TeleportSettings(requiredBoolean(config, "moderator.teleport-to-player-enabled")),
                new PluginSettings.DatabaseSettings(requiredString(config, "database.file"), requiredInt(config, "database.history-limit")),
                actions,
                requiredBoolean(config, "debug"),
                requiredString(config, "locale")
        );
    }

    private void migrateConfig(YamlConfiguration config) throws IOException {
        if (!config.isInt("version")) {
            return;
        }
        int version = config.getInt("version");
        if (version >= CURRENT_CONFIG_VERSION) {
            return;
        }
        if (version != 4) {
            logger.warning("CloverCheck config version " + version + " is older than the supported automatic migration path; values will be validated without modification.");
            return;
        }

        boolean defaultQuitPolicy = "NOTIFY_ONLY".equalsIgnoreCase(config.getString("quit.policy", ""));
        boolean defaultLeftActions = config.isList("actions.left") && config.getStringList("actions.left").isEmpty();
        boolean defaultConfessedActions = config.isList("actions.confessed") && config.getStringList("actions.confessed").isEmpty();
        if (defaultQuitPolicy && defaultLeftActions && defaultConfessedActions) {
            config.set("quit.policy", "EXECUTE_COMMANDS");
            config.set("actions.left", List.of(DEFAULT_LEFT_ACTION));
            config.set("actions.confessed", List.of(DEFAULT_CONFESSED_ACTION));
            logger.info("Migrated CloverCheck punishment defaults: leaving a check and confirmed confession now execute configured actions.");
        } else {
            logger.info("Preserved customized CloverCheck quit/action settings while migrating config version 4 to 5.");
        }
        config.set("version", CURRENT_CONFIG_VERSION);
        config.save(file);
    }

    private static Set<String> readWhitelist(YamlConfiguration config) {
        if (!config.isList("commands.whitelist")) {
            throw new IllegalArgumentException("commands.whitelist must be a YAML list");
        }
        Set<String> whitelist = new LinkedHashSet<>();
        for (String raw : config.getStringList("commands.whitelist")) {
            String root = CommandNormalizer.root(raw);
            if (root.isBlank()) {
                throw new IllegalArgumentException("commands.whitelist contains an invalid command: " + raw);
            }
            whitelist.add(root);
        }
        whitelist.add("check");
        whitelist.add("clovercheck");
        return whitelist;
    }

    private static int requiredInt(YamlConfiguration config, String key) {
        if (!config.isInt(key)) throw new IllegalArgumentException(key + " must be an integer");
        return config.getInt(key);
    }

    private static double requiredDouble(YamlConfiguration config, String key) {
        if (!config.isDouble(key) && !config.isInt(key)) throw new IllegalArgumentException(key + " must be a number");
        return config.getDouble(key);
    }

    private static boolean requiredBoolean(YamlConfiguration config, String key) {
        if (!config.isBoolean(key)) throw new IllegalArgumentException(key + " must be true or false");
        return config.getBoolean(key);
    }

    private static boolean optionalBoolean(YamlConfiguration config, String key, boolean defaultValue) {
        if (!config.contains(key)) return defaultValue;
        if (!config.isBoolean(key)) throw new IllegalArgumentException(key + " must be true or false");
        return config.getBoolean(key);
    }

    private static String requiredString(YamlConfiguration config, String key) {
        if (!config.isString(key)) throw new IllegalArgumentException(key + " must be a string");
        String value = config.getString(key, "").trim();
        if (value.isBlank()) throw new IllegalArgumentException(key + " must not be blank");
        return value;
    }

    private static <E extends Enum<E>> E enumValue(YamlConfiguration config, String key, Class<E> type) {
        String raw = requiredString(config, key).toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(key + " has unsupported value: " + raw);
        }
    }
}
