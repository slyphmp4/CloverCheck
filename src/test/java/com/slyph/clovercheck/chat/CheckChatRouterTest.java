package com.slyph.clovercheck.chat;

import com.slyph.clovercheck.session.CheckSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CheckChatRouterTest {
    @Test
    void checkedPlayerRouteTakesPriorityOverModeratorRoute() {
        UUID participant = UUID.randomUUID();
        CheckSession ownCheck = session(participant, UUID.randomUUID(), "Checked");
        CheckSession moderatedCheck = session(UUID.randomUUID(), participant, "Other");

        CheckChatRouter.Resolution result = CheckChatRouter.resolve(List.of(moderatedCheck, ownCheck), participant);

        assertEquals(CheckChatRouter.Status.ACTIVE, result.status());
        assertEquals(CheckChatRouter.Role.CHECKED, result.role());
        assertSame(ownCheck, result.session());
    }

    @Test
    void routesModeratorWhenExactlyOneSessionMatches() {
        UUID moderator = UUID.randomUUID();
        CheckSession session = session(UUID.randomUUID(), moderator, "Checked");

        CheckChatRouter.Resolution result = CheckChatRouter.resolve(List.of(session), moderator);

        assertEquals(CheckChatRouter.Status.ACTIVE, result.status());
        assertEquals(CheckChatRouter.Role.MODERATOR, result.role());
        assertSame(session, result.session());
    }

    @Test
    void blocksAmbiguousModeratorChat() {
        UUID moderator = UUID.randomUUID();
        CheckSession first = session(UUID.randomUUID(), moderator, "One");
        CheckSession second = session(UUID.randomUUID(), moderator, "Two");

        CheckChatRouter.Resolution result = CheckChatRouter.resolve(List.of(first, second), moderator);

        assertEquals(CheckChatRouter.Status.AMBIGUOUS, result.status());
    }

    @Test
    void ignoresUnrelatedPlayer() {
        CheckSession session = session(UUID.randomUUID(), UUID.randomUUID(), "Checked");

        CheckChatRouter.Resolution result = CheckChatRouter.resolve(List.of(session), UUID.randomUUID());

        assertEquals(CheckChatRouter.Status.NONE, result.status());
    }

    private static CheckSession session(UUID playerId, UUID moderatorId, String playerName) {
        return CheckSession.create(
                playerId,
                playerName,
                moderatorId,
                "Moderator",
                Instant.parse("2026-09-09T00:00:00Z"),
                Duration.ofMinutes(15),
                "Test",
                null
        );
    }
}
