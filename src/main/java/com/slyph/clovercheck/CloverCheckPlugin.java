package com.slyph.clovercheck;

import com.slyph.clovercheck.command.CheckCommand;
import com.slyph.clovercheck.listener.CheckProtectionListener;
import com.slyph.clovercheck.model.CheckSession;
import com.slyph.clovercheck.service.CheckService;
import com.slyph.clovercheck.service.CheckStorage;
import com.slyph.clovercheck.service.MessageService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class CloverCheckPlugin extends JavaPlugin {
    private MessageService messages;
    private CheckService checks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("hovers.yml", false);
        messages = new MessageService(this);
        if (!messages.reload()) {
            getLogger().severe("CloverCheck cannot start because message files are invalid.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CheckStorage storage = new CheckStorage(this);
        List<CheckSession> restored = storage.load();
        checks = new CheckService(this, messages, storage, restored);
        PluginCommand command = getCommand("check");
        if (command == null) {
            getLogger().severe("The check command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CheckCommand checkCommand = new CheckCommand(this, messages, checks);
        command.setExecutor(checkCommand);
        command.setTabCompleter(checkCommand);
        getServer().getPluginManager().registerEvents(new CheckProtectionListener(checks, messages), this);
        checks.startTicker();
        checks.resumeOnlineSessions();
        getLogger().info("CloverCheck enabled with " + restored.size() + " restored active check(s).");
    }

    @Override
    public void onDisable() {
        if (checks != null) checks.shutdown();
    }

    public boolean reloadPlugin() {
        reloadConfig();
        boolean messagesReloaded = messages.reload();
        checks.reloadSettings();
        return messagesReloaded;
    }
}
