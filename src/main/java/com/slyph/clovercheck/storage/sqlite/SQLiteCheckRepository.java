package com.slyph.clovercheck.storage.sqlite;

import com.slyph.clovercheck.model.AuditEventType;
import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.CheckState;
import com.slyph.clovercheck.model.StoredLocation;
import com.slyph.clovercheck.session.CheckSessionSnapshot;
import com.slyph.clovercheck.storage.CheckRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SQLiteCheckRepository implements CheckRepository {
    private static final String CREATE_CHECKS = """
            CREATE TABLE IF NOT EXISTS checks (
                id TEXT PRIMARY KEY,
                player_uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                moderator_uuid TEXT,
                moderator_name TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                deadline_at INTEGER NOT NULL,
                ended_at INTEGER,
                reason TEXT NOT NULL,
                state TEXT NOT NULL,
                result TEXT,
                comment TEXT NOT NULL,
                disconnect_flag INTEGER NOT NULL,
                disconnected_at INTEGER,
                rejoined_at INTEGER,
                updated_at INTEGER NOT NULL,
                origin_world TEXT,
                origin_x REAL,
                origin_y REAL,
                origin_z REAL,
                origin_yaw REAL,
                origin_pitch REAL
            )
            """;
    private static final String CREATE_AUDIT = """
            CREATE TABLE IF NOT EXISTS audit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                actor_uuid TEXT,
                actor_name TEXT NOT NULL,
                event_time INTEGER NOT NULL,
                details TEXT NOT NULL
            )
            """;
    private static final String UPSERT = """
            INSERT INTO checks (
                id, player_uuid, player_name, moderator_uuid, moderator_name,
                started_at, deadline_at, ended_at, reason, state, result, comment,
                disconnect_flag, disconnected_at, rejoined_at, updated_at,
                origin_world, origin_x, origin_y, origin_z, origin_yaw, origin_pitch
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                player_uuid = excluded.player_uuid,
                player_name = excluded.player_name,
                moderator_uuid = excluded.moderator_uuid,
                moderator_name = excluded.moderator_name,
                started_at = excluded.started_at,
                deadline_at = excluded.deadline_at,
                ended_at = excluded.ended_at,
                reason = excluded.reason,
                state = excluded.state,
                result = excluded.result,
                comment = excluded.comment,
                disconnect_flag = excluded.disconnect_flag,
                disconnected_at = excluded.disconnected_at,
                rejoined_at = excluded.rejoined_at,
                updated_at = excluded.updated_at,
                origin_world = excluded.origin_world,
                origin_x = excluded.origin_x,
                origin_y = excluded.origin_y,
                origin_z = excluded.origin_z,
                origin_yaw = excluded.origin_yaw,
                origin_pitch = excluded.origin_pitch
            """;

    private final Path databasePath;
    private final Logger logger;
    private final ExecutorService executor;
    private Connection connection;

    public SQLiteCheckRepository(Path databasePath, Logger logger) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CloverCheck-SQLite");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<List<CheckSessionSnapshot>> initializeAndLoadActive() {
        return supplyAsync(() -> {
            initialize();
            return loadActive();
        });
    }

    @Override
    public CompletableFuture<Void> upsert(CheckSessionSnapshot snapshot) {
        return runAsync(() -> upsertNow(snapshot));
    }

    @Override
    public CompletableFuture<Void> appendAudit(
            String sessionId,
            AuditEventType type,
            UUID actorId,
            String actorName,
            Instant timestamp,
            String details
    ) {
        return runAsync(() -> {
            requireConnection();
            String sql = "INSERT INTO audit_events (session_id, event_type, actor_uuid, actor_name, event_time, details) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, sessionId);
                statement.setString(2, type.name());
                setNullableString(statement, 3, actorId == null ? null : actorId.toString());
                statement.setString(4, actorName);
                statement.setLong(5, timestamp.toEpochMilli());
                statement.setString(6, details);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<List<CheckSessionSnapshot>> history(String playerQuery, int limit) {
        return supplyAsync(() -> {
            requireConnection();
            UUID parsedUuid = parseUuid(playerQuery);
            String sql = parsedUuid == null
                    ? "SELECT * FROM checks WHERE lower(player_name) = lower(?) ORDER BY started_at DESC LIMIT ?"
                    : "SELECT * FROM checks WHERE player_uuid = ? ORDER BY started_at DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, parsedUuid == null ? playerQuery : parsedUuid.toString());
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return readAll(resultSet);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<CheckSessionSnapshot>> findById(String id) {
        return supplyAsync(() -> {
            requireConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM checks WHERE id = ?")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void shutdown(Collection<CheckSessionSnapshot> activeSessions, Duration timeout) {
        List<CheckSessionSnapshot> snapshots = List.copyOf(activeSessions);
        CompletableFuture<Void> closeFuture = runAsync(() -> {
            if (connection != null) {
                for (CheckSessionSnapshot snapshot : snapshots) {
                    upsertNow(snapshot);
                }
                connection.close();
                connection = null;
            }
        });
        try {
            closeFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            logger.severe("Timed out while closing CloverCheck SQLite storage.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while closing CloverCheck SQLite storage.");
        } catch (java.util.concurrent.ExecutionException exception) {
            logger.log(Level.SEVERE, "Failed to close CloverCheck SQLite storage.", exception.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private void initialize() throws Exception {
        Path parent = databasePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute(CREATE_CHECKS);
            statement.execute(CREATE_AUDIT);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_checks_player_uuid ON checks(player_uuid, started_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_checks_player_name ON checks(player_name, started_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_audit_session ON audit_events(session_id, event_time)");
        }
    }

    private List<CheckSessionSnapshot> loadActive() throws SQLException {
        requireConnection();
        String sql = "SELECT * FROM checks WHERE state IN ('STARTING', 'ACTIVE', 'DISCONNECTED') ORDER BY started_at";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return readAll(resultSet);
        }
    }

    private void upsertNow(CheckSessionSnapshot snapshot) throws SQLException {
        requireConnection();
        try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            bind(statement, snapshot);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, CheckSessionSnapshot snapshot) throws SQLException {
        statement.setString(1, snapshot.id());
        statement.setString(2, snapshot.playerId().toString());
        statement.setString(3, snapshot.playerName());
        setNullableString(statement, 4, snapshot.moderatorId() == null ? null : snapshot.moderatorId().toString());
        statement.setString(5, snapshot.moderatorName());
        statement.setLong(6, snapshot.startedAt().toEpochMilli());
        statement.setLong(7, snapshot.deadlineAt().toEpochMilli());
        setNullableLong(statement, 8, snapshot.endedAt());
        statement.setString(9, snapshot.reason());
        statement.setString(10, snapshot.state().name());
        setNullableString(statement, 11, snapshot.result() == null ? null : snapshot.result().name());
        statement.setString(12, snapshot.comment());
        statement.setInt(13, snapshot.disconnected() ? 1 : 0);
        setNullableLong(statement, 14, snapshot.disconnectedAt());
        setNullableLong(statement, 15, snapshot.rejoinedAt());
        statement.setLong(16, snapshot.updatedAt().toEpochMilli());
        StoredLocation origin = snapshot.origin();
        if (origin == null) {
            statement.setNull(17, Types.VARCHAR);
            for (int index = 18; index <= 22; index++) {
                statement.setNull(index, Types.REAL);
            }
        } else {
            statement.setString(17, origin.world());
            statement.setDouble(18, origin.x());
            statement.setDouble(19, origin.y());
            statement.setDouble(20, origin.z());
            statement.setFloat(21, origin.yaw());
            statement.setFloat(22, origin.pitch());
        }
    }

    private static List<CheckSessionSnapshot> readAll(ResultSet resultSet) throws SQLException {
        List<CheckSessionSnapshot> snapshots = new ArrayList<>();
        while (resultSet.next()) {
            snapshots.add(read(resultSet));
        }
        return List.copyOf(snapshots);
    }

    private static CheckSessionSnapshot read(ResultSet resultSet) throws SQLException {
        String moderatorUuid = resultSet.getString("moderator_uuid");
        String resultName = resultSet.getString("result");
        String originWorld = resultSet.getString("origin_world");
        StoredLocation origin = originWorld == null ? null : new StoredLocation(
                originWorld,
                resultSet.getDouble("origin_x"),
                resultSet.getDouble("origin_y"),
                resultSet.getDouble("origin_z"),
                resultSet.getFloat("origin_yaw"),
                resultSet.getFloat("origin_pitch")
        );
        return new CheckSessionSnapshot(
                resultSet.getString("id"),
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"),
                moderatorUuid == null ? null : UUID.fromString(moderatorUuid),
                resultSet.getString("moderator_name"),
                Instant.ofEpochMilli(resultSet.getLong("started_at")),
                Instant.ofEpochMilli(resultSet.getLong("deadline_at")),
                nullableInstant(resultSet, "ended_at"),
                resultSet.getString("reason"),
                CheckState.valueOf(resultSet.getString("state").toUpperCase(Locale.ROOT)),
                resultName == null ? null : CheckResult.valueOf(resultName.toUpperCase(Locale.ROOT)),
                resultSet.getString("comment"),
                resultSet.getInt("disconnect_flag") != 0,
                nullableInstant(resultSet, "disconnected_at"),
                nullableInstant(resultSet, "rejoined_at"),
                Instant.ofEpochMilli(resultSet.getLong("updated_at"))
        );
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value.toEpochMilli());
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void requireConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("SQLite connection is not initialized");
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private CompletableFuture<Void> runAsync(SqlTask task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @FunctionalInterface
    private interface SqlTask {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }
}
