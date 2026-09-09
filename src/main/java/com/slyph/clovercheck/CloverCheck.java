package com.slyph.clovercheck;

import com.slyph.clovercheck.chat.CheckChatService;
import com.slyph.clovercheck.command.CheckCommand;
import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.listener.CheckChatListener;
import com.slyph.clovercheck.listener.CheckProtectionListener;
import com.slyph.clovercheck.listener.LegacyCheckChatBridge;
import com.slyph.clovercheck.service.ActionService;
import com.slyph.clovercheck.service.AuditService;
import com.slyph.clovercheck.service.CheckIsolationService;
import com.slyph.clovercheck.service.CheckSessionService;
import com.slyph.clovercheck.service.MessageService;
import com.slyph.clovercheck.session.CheckSessionSnapshot;
import com.slyph.clovercheck.storage.CheckRepository;
import com.slyph.clovercheck.storage.sqlite.SQLiteCheckRepository;
import com.slyph.clovercheck.ui.CheckUiService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class CloverCheck extends JavaPlugin {
    public record ReloadOutcome(boolean success, boolean databaseRestartRequired) {
    }

    private ConfigService configService;
    private MessageService messageService;
    private CheckRepository repository;
    private CheckSessionService checkSessions;
    private CheckChatService checkChatService;
    private CheckIsolationService checkIsolationService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("chat.yml", false);

        configService = new ConfigService(this);
        messageService = new MessageService(this);
        if (!configService.loadInitial() || !messageService.reload()) {
            getLogger().severe("CloverCheck startup stopped because configuration is invalid.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        repository = new SQLiteCheckRepository(
                getDataFolder().toPath().resolve(configService.settings().database().file()),
                getLogger()
        );
        repository.initializeAndLoadActive().whenComplete((restored, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                getLogger().log(Level.SEVERE, "Failed to initialize CloverCheck SQLite storage.", cause);
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                }
                return;
            }
            if (isEnabled()) {
                Bukkit.getScheduler().runTask(this, () -> bootstrap(restored));
            }
        });
    }

    @Override
    public void onDisable() {
        if (checkIsolationService != null) {
            checkIsolationService.shutdown();
        }
        List<CheckSessionSnapshot> active = checkSessions == null ? List.of() : checkSessions.shutdown();
        if (repository != null) {
            repository.shutdown(active, Duration.ofSeconds(5));
        }
    }

    public ReloadOutcome reloadPlugin() {
        boolean messagesReloaded = messageService.reload();
        boolean chatReloaded = checkChatService == null || checkChatService.reload();
        ConfigService.ReloadResult configReloaded = configService.reload();
        boolean success = messagesReloaded && chatReloaded && configReloaded.success();
        if (success && checkSessions != null) {
            checkSessions.onSettingsReload();
        }
        return new ReloadOutcome(success, configReloaded.databaseRestartRequired());
    }

    private void bootstrap(List<CheckSessionSnapshot> restored) {
        if (!isEnabled()) return;
        boolean fabricRuntime = isFabricRuntime();
        AuditService audit = new AuditService(repository, getLogger());
        ActionService actions = new ActionService(configService, audit, getLogger());
        CheckUiService ui = new CheckUiService(configService, messageService, getLogger(), fabricRuntime);
        checkSessions = new CheckSessionService(this, configService, messageService, repository, audit, actions, ui, restored);
        checkChatService = new CheckChatService(this, messageService, checkSessions);
        checkIsolationService = new CheckIsolationService(this, checkSessions, getLogger(), fabricRuntime);
        if (!checkChatService.reload()) {
            getLogger().severe("CloverCheck startup stopped because chat.yml is invalid.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        checkSessions.resumeOnlineSessions();
        getServer().getPluginManager().registerEvents(new CheckProtectionListener(checkSessions, messageService, checkIsolationService), this);
        if (fabricRuntime) {
            if (!new LegacyCheckChatBridge(this, checkChatService).register()) {
                getLogger().severe("CloverCheck cannot safely isolate check chat on this Fabric/Cardboard runtime.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        } else {
            getServer().getPluginManager().registerEvents(new CheckChatListener(checkChatService), this);
        }

        PluginCommand command = getCommand("check");
        if (command == null) {
            getLogger().severe("The check command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CheckCommand checkCommand = new CheckCommand(this, messageService, checkSessions);
        command.setExecutor(checkCommand);
        command.setTabCompleter(checkCommand);
        checkSessions.startTicker();
        checkIsolationService.start();
        getLogger().info("CloverCheck initialized with " + restored.size() + " persisted active session(s).");
    }

    private boolean isFabricRuntime() {
        try {
            Class.forName("net.fabricmc.loader.api.FabricLoader", false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
