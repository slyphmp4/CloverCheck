package com.slyph.clovercheck;

import com.slyph.clovercheck.chat.CheckChatService;
import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.service.CheckSessionService;
import com.slyph.clovercheck.service.MessageService;
import com.slyph.clovercheck.storage.CheckRepository;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;
import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CloverCheckReloadTest {
    @Test
    void uiCleanupFailureStillClosesStorage() throws Exception {
        CloverCheck plugin = mock(CloverCheck.class);
        doCallRealMethod().when(plugin).onDisable();
        CheckSessionService sessions = mock(CheckSessionService.class);
        CheckRepository repository = mock(CheckRepository.class);
        set(plugin, "checkSessions", sessions);
        set(plugin, "repository", repository);
        when(sessions.shutdown()).thenThrow(new IllegalStateException("UI cleanup failed"));
        assertThrows(IllegalStateException.class, plugin::onDisable);
        verify(repository).shutdown(List.of(), Duration.ofSeconds(5));
    }

    @Test
    void invalidConfigDoesNotApplyPreparedMessageOrChatChanges() throws Exception {
        CloverCheck plugin = mock(CloverCheck.class);
        when(plugin.reloadPlugin()).thenCallRealMethod();
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        ConfigService config = mock(ConfigService.class);
        MessageService messages = mock(MessageService.class);
        CheckChatService chat = mock(CheckChatService.class);
        CheckSessionService sessions = mock(CheckSessionService.class);
        set(plugin, "configService", config);
        set(plugin, "messageService", messages);
        set(plugin, "checkChatService", chat);
        set(plugin, "checkSessions", sessions);
        Runnable applyMessages = mock(Runnable.class);
        Runnable applyChat = mock(Runnable.class);
        when(messages.prepareReload()).thenReturn(applyMessages);
        when(chat.prepareReload()).thenReturn(applyChat);
        when(config.prepareReload()).thenThrow(new InvalidConfigurationException("broken YAML"));
        assertFalse(plugin.reloadPlugin().success());
        verifyNoInteractions(applyMessages, applyChat, sessions);
    }

    @Test
    void successfulReloadAppliesAllPreparedSettingsAndRefreshesSessions() throws Exception {
        CloverCheck plugin = mock(CloverCheck.class);
        when(plugin.reloadPlugin()).thenCallRealMethod();
        ConfigService config = mock(ConfigService.class);
        MessageService messages = mock(MessageService.class);
        CheckChatService chat = mock(CheckChatService.class);
        CheckSessionService sessions = mock(CheckSessionService.class);
        set(plugin, "configService", config);
        set(plugin, "messageService", messages);
        set(plugin, "checkChatService", chat);
        set(plugin, "checkSessions", sessions);
        Runnable applyMessages = mock(Runnable.class);
        Runnable applyChat = mock(Runnable.class);
        Runnable applyConfig = mock(Runnable.class);
        when(messages.prepareReload()).thenReturn(applyMessages);
        when(chat.prepareReload()).thenReturn(applyChat);
        when(config.prepareReload()).thenReturn(new ConfigService.PreparedReload(applyConfig, true));
        assertEquals(new CloverCheck.ReloadOutcome(true, true), plugin.reloadPlugin());
        verify(applyMessages).run();
        verify(applyChat).run();
        verify(applyConfig).run();
        verify(sessions).onSettingsReload();
    }

    private static void set(CloverCheck plugin, String name, Object value) throws Exception {
        var field = CloverCheck.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
