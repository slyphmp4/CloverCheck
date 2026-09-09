package com.slyph.clovercheck.service;

import com.slyph.clovercheck.CloverCheckPlugin;
import com.slyph.clovercheck.model.CheckSession;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class CheckStorage {
    private final CloverCheckPlugin plugin;
    private final File file;
    private final ExecutorService executor;

    public CheckStorage(CloverCheckPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "checks.yml");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CloverCheck-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    public List<CheckSession> load() {
        if (!file.exists()) {
            return List.of();
        }

        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Failed to load checks.yml: " + exception.getMessage());
            return List.of();
        }

        ConfigurationSection section = configuration.getConfigurationSection("checks");
        if (section == null) {
            return List.of();
        }

        List<CheckSession> sessions = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            try {
                String base = "checks." + key;
                UUID playerId = UUID.fromString(require(configuration.getString(base + ".player-uuid")));
                String playerName = require(configuration.getString(base + ".player-name"));
                String staffUuidRaw = configuration.getString(base + ".staff-uuid");
                UUID staffId = staffUuidRaw == null || staffUuidRaw.isBlank() ? null : UUID.fromString(staffUuidRaw);
                String staffName = require(configuration.getString(base + ".staff-name"));
                Instant startedAt = Instant.ofEpochMilli(configuration.getLong(base + ".started-at"));
                Instant deadlineAt = Instant.ofEpochMilli(configuration.getLong(base + ".deadline-at"));
                sessions.add(new CheckSession(key, playerId, playerName, staffId, staffName, startedAt, deadlineAt));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring malformed stored check " + key + ": " + exception.getMessage());
            }
        }
        return sessions;
    }

    public void saveAsync(Collection<CheckSession> sessions) {
        List<CheckSession> snapshot = List.copyOf(sessions);
        executor.execute(() -> save(snapshot));
    }

    public void shutdown(Collection<CheckSession> sessions) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        save(List.copyOf(sessions));
    }

    private void save(List<CheckSession> sessions) {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", 1);
        for (CheckSession session : sessions) {
            String base = "checks." + session.id();
            configuration.set(base + ".player-uuid", session.playerId().toString());
            configuration.set(base + ".player-name", session.playerName());
            configuration.set(base + ".staff-uuid", session.staffId() == null ? null : session.staffId().toString());
            configuration.set(base + ".staff-name", session.staffName());
            configuration.set(base + ".started-at", session.startedAt().toEpochMilli());
            configuration.set(base + ".deadline-at", session.deadlineAt().toEpochMilli());
        }

        try {
            configuration.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to save checks.yml: " + exception.getMessage());
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("required value is missing");
        }
        return value;
    }
}
