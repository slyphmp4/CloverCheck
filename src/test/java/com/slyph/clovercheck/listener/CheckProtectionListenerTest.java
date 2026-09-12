package com.slyph.clovercheck.listener;

import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.service.CheckSessionService;
import com.slyph.clovercheck.service.MessageService;
import com.slyph.clovercheck.session.CheckSession;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckProtectionListenerTest {
    private final CheckSessionService checks = mock(CheckSessionService.class);
    private final PluginSettings.FreezeSettings settings = mock(PluginSettings.FreezeSettings.class);
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final UUID id = UUID.randomUUID();
    private final CheckProtectionListener listener = new CheckProtectionListener(checks, mock(MessageService.class));

    private void startSession() {
        when(player.getUniqueId()).thenReturn(id);
        when(checks.freezeSettings()).thenReturn(settings);
        when(settings.movement()).thenReturn(true);
        when(checks.activeSession(id)).thenReturn(Optional.of(CheckSession.create(id, "Player", null, "CONSOLE",
                Instant.now(), Duration.ofMinutes(15), "check", null)));
    }

    private Location move(double from, double to) {
        PlayerMoveEvent event = new PlayerMoveEvent(player, new Location(world, from, 64, 0), new Location(world, to, 64, 0, 90, 20));
        listener.onMove(event);
        return event.getTo();
    }

    @Test
    void nextSessionDoesNotReusePreviousAnchorAndCameraCanRotate() {
        startSession();
        Location first = move(10, 11);
        assertEquals(10, first.getX());
        assertEquals(90, first.getYaw());
        assertEquals(20, first.getPitch());
        startSession();
        assertEquals(50, move(50, 51).getX());
    }

    @Test
    void reenablingMovementUsesCurrentPosition() {
        startSession();
        move(10, 11);
        when(settings.movement()).thenReturn(false);
        assertEquals(31, move(30, 31).getX());
        when(settings.movement()).thenReturn(true);
        assertEquals(40, move(40, 41).getX());
    }

    @Test
    void allowedTeleportUpdatesAnchor() {
        startSession();
        move(10, 11);
        PlayerTeleportEvent teleport = new PlayerTeleportEvent(player, new Location(world, 10, 64, 0),
                new Location(world, 100, 64, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
        listener.onTeleport(teleport);
        assertFalse(teleport.isCancelled());
        listener.onTeleportCompleted(teleport);
        assertEquals(100, move(100, 101).getX());
    }

    @Test
    void blockedTeleportKeepsAnchor() {
        startSession();
        when(settings.teleport()).thenReturn(true);
        move(10, 11);
        PlayerTeleportEvent teleport = new PlayerTeleportEvent(player, new Location(world, 10, 64, 0),
                new Location(world, 100, 64, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
        listener.onTeleport(teleport);
        assertTrue(teleport.isCancelled());
        // Bukkit skips the MONITOR handler because ignoreCancelled=true.
        assertEquals(10, move(10, 11).getX());
    }
}
