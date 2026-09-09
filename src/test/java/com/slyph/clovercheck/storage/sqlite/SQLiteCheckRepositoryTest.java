package com.slyph.clovercheck.storage.sqlite;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.session.CheckSession;
import com.slyph.clovercheck.session.CheckSessionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteCheckRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsHistoryAndLoadsActiveSessions() {
        SQLiteCheckRepository repository = new SQLiteCheckRepository(tempDir.resolve("checks.db"), Logger.getLogger("test"));
        assertTrue(repository.initializeAndLoadActive().join().isEmpty());

        CheckSession active = create("ActivePlayer");
        active.activate(Instant.parse("2026-09-09T00:00:01Z"));
        repository.upsert(active.snapshot()).join();

        CheckSession completed = create("HistoryPlayer");
        completed.activate(Instant.parse("2026-09-09T00:00:01Z"));
        completed.complete(CheckResult.CLEAN, "checked", Instant.parse("2026-09-09T00:05:00Z"));
        repository.upsert(completed.snapshot()).join();

        List<CheckSessionSnapshot> history = repository.history("HistoryPlayer", 10).join();
        assertEquals(1, history.size());
        assertEquals(CheckResult.CLEAN, history.getFirst().result());
        assertTrue(repository.findById(completed.id()).join().isPresent());

        repository.shutdown(List.of(active.snapshot()), Duration.ofSeconds(5));

        SQLiteCheckRepository reopened = new SQLiteCheckRepository(tempDir.resolve("checks.db"), Logger.getLogger("test"));
        List<CheckSessionSnapshot> restored = reopened.initializeAndLoadActive().join();
        assertEquals(1, restored.size());
        assertEquals("ActivePlayer", restored.getFirst().playerName());
        reopened.shutdown(List.of(), Duration.ofSeconds(5));
    }

    private static CheckSession create(String playerName) {
        return CheckSession.create(
                UUID.randomUUID(),
                playerName,
                UUID.randomUUID(),
                "Moderator",
                Instant.parse("2026-09-09T00:00:00Z"),
                Duration.ofMinutes(15),
                "Manual check",
                null
        );
    }
}
