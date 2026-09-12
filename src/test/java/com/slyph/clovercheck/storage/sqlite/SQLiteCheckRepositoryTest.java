package com.slyph.clovercheck.storage.sqlite;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.session.CheckSession;
import com.slyph.clovercheck.session.CheckSessionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void mixedCaseHistoryUsesIndexWithoutTemporarySort() throws Exception {
        Path file = tempDir.resolve("history.db");
        SQLiteCheckRepository repository = new SQLiteCheckRepository(file, Logger.getLogger("test"));
        repository.initializeAndLoadActive().join();
        try {
            CheckSession older = create("MixedCase");
            CheckSession newer = CheckSession.create(older.playerId(), "MIXEDCASE", null, "CONSOLE",
                    older.startedAt().plusSeconds(1), Duration.ofMinutes(15), "check", null);
            repository.upsert(older.snapshot()).join();
            repository.upsert(newer.snapshot()).join();
            assertEquals(List.of(newer.snapshot()), repository.history("mixedcase", 1).join());
            assertEquals(2, repository.history(older.playerId().toString(), 10).join().size());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                 var statement = connection.prepareStatement("EXPLAIN QUERY PLAN SELECT * FROM checks WHERE lower(player_name) = lower(?) ORDER BY started_at DESC LIMIT ?")) {
                statement.setString(1, "mixedcase");
                statement.setInt(2, 10);
                try (var rows = statement.executeQuery()) {
                    StringBuilder plan = new StringBuilder();
                    while (rows.next()) plan.append(rows.getString("detail"));
                    assertTrue(plan.toString().contains("idx_checks_player_name_lower"), plan.toString());
                    assertFalse(plan.toString().contains("TEMP B-TREE"), plan.toString());
                }
            }
        } finally {
            repository.shutdown(List.of(), Duration.ofSeconds(5));
        }
    }

    @Test
    void failedFinalWriteStillClosesConnectionAndSavesOtherSnapshots() throws Exception {
        Path file = tempDir.resolve("failure.db");
        SQLiteCheckRepository repository = new SQLiteCheckRepository(file, Logger.getLogger("test"));
        repository.initializeAndLoadActive().join();
        Connection ownedConnection = connectionOf(repository);
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = writer.createStatement()) {
            statement.execute("CREATE TRIGGER reject_bad BEFORE INSERT ON checks WHEN NEW.player_name = 'Bad' BEGIN SELECT RAISE(FAIL, 'test write failure'); END");
        }
        repository.shutdown(List.of(create("Bad").snapshot(), create("Good").snapshot()), Duration.ofSeconds(5));
        assertTrue(ownedConnection.isClosed());
        SQLiteCheckRepository reopened = new SQLiteCheckRepository(file, Logger.getLogger("test"));
        try {
            assertEquals("Good", reopened.initializeAndLoadActive().join().getFirst().playerName());
        } finally {
            reopened.shutdown(List.of(), Duration.ofSeconds(5));
        }
    }

    @Test
    void repeatedShutdownIsSafeAndLaterQueriesReturnFailedFuture() {
        SQLiteCheckRepository repository = new SQLiteCheckRepository(tempDir.resolve("closed.db"), Logger.getLogger("test"));
        repository.initializeAndLoadActive().join();
        repository.shutdown(List.of(), Duration.ofSeconds(5));
        repository.shutdown(List.of(), Duration.ofSeconds(5));
        var query = repository.history("Player", 10);
        assertThrows(CompletionException.class, query::join);
    }

    @Test
    void timeoutDoesNotDiscardQueuedClose() throws Exception {
        SQLiteCheckRepository repository = new SQLiteCheckRepository(tempDir.resolve("timeout.db"), Logger.getLogger("test"));
        repository.initializeAndLoadActive().join();
        Connection connection = connectionOf(repository);
        var field = SQLiteCheckRepository.class.getDeclaredField("executor");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(repository);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> {
            blocked.countDown();
            try { release.await(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            repository.shutdown(List.of(create("Queued").snapshot()), Duration.ZERO);
        } finally {
            release.countDown();
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(connection.isClosed());
        SQLiteCheckRepository reopened = new SQLiteCheckRepository(tempDir.resolve("timeout.db"), Logger.getLogger("test"));
        try {
            assertEquals("Queued", reopened.initializeAndLoadActive().join().getFirst().playerName());
        } finally {
            reopened.shutdown(List.of(), Duration.ofSeconds(5));
        }
    }

    private static Connection connectionOf(SQLiteCheckRepository repository) throws Exception {
        var field = SQLiteCheckRepository.class.getDeclaredField("connection");
        field.setAccessible(true);
        return (Connection) field.get(repository);
    }
}
