package com.slyph.clovercheck.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionRegistryTest {
    @Test
    void duplicatePlayerStartIsRejected() {
        SessionRegistry registry = new SessionRegistry();
        UUID playerId = UUID.randomUUID();
        assertEquals(SessionRegistry.RegisterResult.ADDED, registry.register(session(playerId, UUID.randomUUID()), true));
        assertEquals(SessionRegistry.RegisterResult.PLAYER_BUSY, registry.register(session(playerId, UUID.randomUUID()), true));
    }

    @Test
    void moderatorConflictIsRejectedWhenPolicyEnabled() {
        SessionRegistry registry = new SessionRegistry();
        UUID moderatorId = UUID.randomUUID();
        assertEquals(SessionRegistry.RegisterResult.ADDED, registry.register(session(UUID.randomUUID(), moderatorId), true));
        assertEquals(SessionRegistry.RegisterResult.MODERATOR_BUSY, registry.register(session(UUID.randomUUID(), moderatorId), true));
    }

    private static CheckSession session(UUID playerId, UUID moderatorId) {
        CheckSession session = CheckSession.create(
                playerId,
                "Player",
                moderatorId,
                "Moderator",
                Instant.parse("2026-09-09T00:00:00Z"),
                Duration.ofMinutes(15),
                "Manual check",
                null
        );
        session.activate(Instant.parse("2026-09-09T00:00:01Z"));
        return session;
    }
}
