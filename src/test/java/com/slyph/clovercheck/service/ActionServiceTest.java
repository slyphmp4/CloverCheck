package com.slyph.clovercheck.service;

import com.slyph.clovercheck.config.ConfigService;
import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.model.AuditEventType;
import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.session.CheckSession;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandException;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ActionServiceTest {
    @Test
    void failingCommandIsAuditedAndDoesNotSkipFollowingActions() {
        ConfigService config = mock(ConfigService.class);
        PluginSettings settings = mock(PluginSettings.class);
        when(config.settings()).thenReturn(settings);
        when(settings.actionCommands(CheckResult.LEFT)).thenReturn(List.of("broken %player%", "unhandled", "ok %player%"));
        AuditService audit = mock(AuditService.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        var snapshot = CheckSession.create(UUID.randomUUID(), "Player", null, "CONSOLE", Instant.now(),
                Duration.ofMinutes(15), "check", null).snapshot();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            bukkit.when(() -> Bukkit.dispatchCommand(console, "broken Player")).thenThrow(new CommandException("test failure"));
            bukkit.when(() -> Bukkit.dispatchCommand(console, "ok Player")).thenReturn(true);
            ActionService actions = new ActionService(config, audit, Logger.getLogger("test"));
            assertDoesNotThrow(() -> actions.execute(snapshot, CheckResult.LEFT, console));
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "ok Player"));
            verify(audit).log(eq(snapshot.id()), eq(AuditEventType.INTERNAL_ERROR), isNull(), eq("CONSOLE"), eq("action command failed root=broken"));
            verify(audit).log(eq(snapshot.id()), eq(AuditEventType.INTERNAL_ERROR), isNull(), eq("CONSOLE"), eq("action command not handled root=unhandled"));
        }
    }
}
