package com.slyph.clovercheck.ui;

import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;

class CheckUiServiceTest {
    @Test
    void reloadAndShutdownOnlyClearUiForParticipatingPlayers() {
        ConfigService config = mock(ConfigService.class);
        PluginSettings settings = mock(PluginSettings.class);
        when(config.settings()).thenReturn(settings);
        when(settings.freeze()).thenReturn(mock(PluginSettings.FreezeSettings.class));
        when(settings.bossBar()).thenReturn(new PluginSettings.BossBarSettings(false, "YELLOW", "PROGRESS"));
        when(settings.title()).thenReturn(new PluginSettings.TitleSettings(true, 0, 600, 0));
        when(settings.actionBar()).thenReturn(new PluginSettings.ActionBarSettings(true));
        MessageService messages = mock(MessageService.class);
        when(messages.component(anyString(), anyMap())).thenReturn(Component.text("check"));
        Player checked = mock(Player.class);
        Player unrelated = mock(Player.class);
        when(checked.getUniqueId()).thenReturn(UUID.randomUUID());
        when(unrelated.getUniqueId()).thenReturn(UUID.randomUUID());
        Server server = mock(Server.class);
        when(server.getName()).thenReturn("Paper");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            CheckUiService ui = new CheckUiService(config, messages, Logger.getLogger("test"));
            ui.showResume(checked, Map.of(), 1.0F);
            verify(checked).showTitle(any(Title.class));
            when(settings.title()).thenReturn(new PluginSettings.TitleSettings(false, 0, 600, 0));
            when(settings.actionBar()).thenReturn(new PluginSettings.ActionBarSettings(false));
            ui.update(checked, Map.of(), 0.5F);
            verify(checked).clearTitle();
            verify(checked).sendActionBar(Component.empty());
            ui.hideAll(List.of(checked, unrelated));
            verify(checked, times(1)).clearTitle();
            verify(unrelated, never()).clearTitle();
            verify(unrelated, never()).sendActionBar(any(Component.class));
        }
    }
}
