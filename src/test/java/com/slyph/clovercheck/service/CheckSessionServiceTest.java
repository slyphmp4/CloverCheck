package com.slyph.clovercheck.service;

import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.model.CheckState;
import com.slyph.clovercheck.model.QuitPolicy;
import com.slyph.clovercheck.session.CheckSession;
import com.slyph.clovercheck.storage.CheckRepository;
import com.slyph.clovercheck.ui.CheckUiService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckSessionServiceTest {
    private final ConfigService config = mock(ConfigService.class);
    private final PluginSettings settings = mock(PluginSettings.class);
    private final MessageService messages = mock(MessageService.class);
    private final ActionService actions = mock(ActionService.class);
    private final CheckRepository repository = mock(CheckRepository.class);
    private final CheckUiService ui = mock(CheckUiService.class);
    private final Player player = mock(Player.class);
    private final CheckSession session = CheckSession.create(UUID.randomUUID(), "Player", null, "CONSOLE",
            Instant.now(), Duration.ofMinutes(15), "check", null);

    private CheckSessionService service() {
        when(config.settings()).thenReturn(settings);
        when(settings.contact()).thenReturn("contact");
        when(settings.staff()).thenReturn(new PluginSettings.StaffSettings(false));
        when(settings.quitPolicy()).thenReturn(QuitPolicy.NOTIFY_ONLY);
        when(player.getUniqueId()).thenReturn(session.playerId());
        when(player.getName()).thenReturn("Renamed");
        when(repository.upsert(any())).thenReturn(CompletableFuture.completedFuture(null));
        return new CheckSessionService(mock(JavaPlugin.class), config, messages, repository,
                mock(AuditService.class), actions, ui, List.of(session.snapshot()));
    }

    @Test
    void disablingConfessionRejectsPreviouslyRequestedConfirmation() {
        session.activate(Instant.now());
        CheckSessionService service = service();
        when(settings.confess()).thenReturn(new PluginSettings.ConfessSettings(true, 30));
        service.requestConfess(player);
        when(settings.confess()).thenReturn(new PluginSettings.ConfessSettings(false, 30));
        service.confirmConfess(player);
        verify(messages).send(player, "confess-disabled");
        verifyNoInteractions(actions);
        assertTrue(service.isChecked(session.playerId()));
        when(settings.confess()).thenReturn(new PluginSettings.ConfessSettings(true, 30));
        service.confirmConfess(player);
        verify(messages).send(player, "confess-expired");
    }

    @Test
    void joiningActivatesRestoredStartingSessionAndQuitCleansUi() {
        CheckSessionService service = service();
        try (var bukkit = mockStatic(Bukkit.class)) {
            service.handleJoin(player);
            var active = service.activeSession(session.playerId()).orElseThrow();
            assertEquals(CheckState.ACTIVE, active.state());
            assertEquals("Renamed", active.playerName());
            service.handleQuit(player);
            assertEquals(CheckState.DISCONNECTED, active.state());
            verify(ui).clear(player);
        }
    }
}
