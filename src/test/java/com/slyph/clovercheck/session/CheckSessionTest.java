package com.slyph.clovercheck.session;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.CheckState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckSessionTest {
    private static CheckSession session() {
        return CheckSession.create(
                UUID.randomUUID(),
                "Player",
                UUID.randomUUID(),
                "Moderator",
                Instant.parse("2026-09-09T00:00:00Z"),
                Duration.ofMinutes(15),
                "Manual check",
                null
        );
    }

    @Test
    void startActivatesSession() {
        CheckSession session = session();
        assertEquals(CheckState.STARTING, session.state());
        assertTrue(session.activate(Instant.parse("2026-09-09T00:00:01Z")));
        assertEquals(CheckState.ACTIVE, session.state());
    }

    @Test
    void cleanCompletionIsTerminal() {
        CheckSession session = activeSession();
        assertTrue(session.complete(CheckResult.CLEAN, "", Instant.parse("2026-09-09T00:01:00Z")));
        assertEquals(CheckResult.CLEAN, session.result());
        assertEquals(CheckState.COMPLETED, session.state());
    }

    @Test
    void cheatsCompletionStoresComment() {
        CheckSession session = activeSession();
        assertTrue(session.complete(CheckResult.CHEATS_FOUND, "Reach", Instant.parse("2026-09-09T00:01:00Z")));
        assertEquals("Reach", session.snapshot().comment());
    }

    @Test
    void cancelUsesCancelledState() {
        CheckSession session = activeSession();
        assertTrue(session.complete(CheckResult.CANCELLED, "mistake", Instant.parse("2026-09-09T00:01:00Z")));
        assertEquals(CheckState.CANCELLED, session.state());
    }

    @Test
    void confessCanCompleteSession() {
        CheckSession session = activeSession();
        assertTrue(session.complete(CheckResult.CONFESSED, "", Instant.parse("2026-09-09T00:01:00Z")));
        assertEquals(CheckResult.CONFESSED, session.result());
    }

    @Test
    void disconnectAndReconnectPreserveSession() {
        CheckSession session = activeSession();
        assertTrue(session.disconnect(Instant.parse("2026-09-09T00:01:00Z")));
        assertEquals(CheckState.DISCONNECTED, session.state());
        assertTrue(session.reconnect("PlayerNew", Instant.parse("2026-09-09T00:02:00Z")));
        assertEquals(CheckState.ACTIVE, session.state());
        assertEquals("PlayerNew", session.playerName());
        assertTrue(session.snapshot().disconnected());
    }

    @Test
    void timeoutCanCompleteDisconnectedSession() {
        CheckSession session = activeSession();
        session.disconnect(Instant.parse("2026-09-09T00:01:00Z"));
        assertTrue(session.complete(CheckResult.TIMEOUT, "", Instant.parse("2026-09-09T00:15:00Z")));
        assertEquals(CheckResult.TIMEOUT, session.result());
    }

    @Test
    void duplicateCompleteIsIdempotent() {
        CheckSession session = activeSession();
        assertTrue(session.complete(CheckResult.CLEAN, "", Instant.parse("2026-09-09T00:01:00Z")));
        assertFalse(session.complete(CheckResult.CHEATS_FOUND, "", Instant.parse("2026-09-09T00:01:01Z")));
        assertEquals(CheckResult.CLEAN, session.result());
    }

    @Test
    void concurrentCompletionAllowsOnlyOneWinner() throws InterruptedException {
        CheckSession session = activeSession();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Thread first = new Thread(() -> completeAfterLatch(session, CheckResult.CLEAN, start, winners));
        Thread second = new Thread(() -> completeAfterLatch(session, CheckResult.CHEATS_FOUND, start, winners));
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();
        assertEquals(1, winners.get());
    }

    private static CheckSession activeSession() {
        CheckSession session = session();
        session.activate(Instant.parse("2026-09-09T00:00:01Z"));
        return session;
    }

    private static void completeAfterLatch(CheckSession session, CheckResult result, CountDownLatch latch, AtomicInteger winners) {
        try {
            latch.await();
            if (session.complete(result, "", Instant.parse("2026-09-09T00:01:00Z"))) {
                winners.incrementAndGet();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
