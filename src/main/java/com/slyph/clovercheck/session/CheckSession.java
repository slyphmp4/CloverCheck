package com.slyph.clovercheck.session;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.CheckState;
import com.slyph.clovercheck.model.StoredLocation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CheckSession {
    private final String id;
    private final UUID playerId;
    private String playerName;
    private final UUID moderatorId;
    private final String moderatorName;
    private final Instant startedAt;
    private final Instant deadlineAt;
    private final String reason;
    private final StoredLocation origin;
    private CheckState state;
    private CheckResult result;
    private String comment;
    private boolean disconnected;
    private Instant disconnectedAt;
    private Instant rejoinedAt;
    private Instant endedAt;
    private Instant updatedAt;

    private CheckSession(
            String id,
            UUID playerId,
            String playerName,
            UUID moderatorId,
            String moderatorName,
            Instant startedAt,
            Instant deadlineAt,
            String reason,
            StoredLocation origin,
            CheckState state,
            CheckResult result,
            String comment,
            boolean disconnected,
            Instant disconnectedAt,
            Instant rejoinedAt,
            Instant endedAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = requireText(playerName, "playerName");
        this.moderatorId = moderatorId;
        this.moderatorName = requireText(moderatorName, "moderatorName");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        this.reason = requireText(reason, "reason");
        this.origin = origin;
        this.state = Objects.requireNonNull(state, "state");
        this.result = result;
        this.comment = comment == null ? "" : comment;
        this.disconnected = disconnected;
        this.disconnectedAt = disconnectedAt;
        this.rejoinedAt = rejoinedAt;
        this.endedAt = endedAt;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        validateState();
    }

    public static CheckSession create(
            UUID playerId,
            String playerName,
            UUID moderatorId,
            String moderatorName,
            Instant now,
            Duration duration,
            String reason,
            StoredLocation origin
    ) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        return new CheckSession(
                UUID.randomUUID().toString(),
                playerId,
                playerName,
                moderatorId,
                moderatorName,
                now,
                now.plus(duration),
                reason,
                origin,
                CheckState.STARTING,
                null,
                "",
                false,
                null,
                null,
                null,
                now
        );
    }

    public static CheckSession restore(CheckSessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new CheckSession(
                snapshot.id(),
                snapshot.playerId(),
                snapshot.playerName(),
                snapshot.moderatorId(),
                snapshot.moderatorName(),
                snapshot.startedAt(),
                snapshot.deadlineAt(),
                snapshot.reason(),
                snapshot.origin(),
                snapshot.state(),
                snapshot.result(),
                snapshot.comment(),
                snapshot.disconnected(),
                snapshot.disconnectedAt(),
                snapshot.rejoinedAt(),
                snapshot.endedAt(),
                snapshot.updatedAt()
        );
    }

    public synchronized boolean activate(Instant now) {
        if (state != CheckState.STARTING) {
            return false;
        }
        state = CheckState.ACTIVE;
        updatedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public synchronized boolean disconnect(Instant now) {
        if (state != CheckState.ACTIVE) {
            return false;
        }
        state = CheckState.DISCONNECTED;
        disconnected = true;
        disconnectedAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
        return true;
    }

    public synchronized boolean reconnect(String currentName, Instant now) {
        if (state != CheckState.DISCONNECTED) {
            return false;
        }
        playerName = requireText(currentName, "currentName");
        state = CheckState.ACTIVE;
        rejoinedAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
        return true;
    }

    public synchronized boolean updatePlayerName(String currentName, Instant now) {
        if (state.isTerminal()) {
            return false;
        }
        String normalized = requireText(currentName, "currentName");
        if (playerName.equals(normalized)) {
            return false;
        }
        playerName = normalized;
        updatedAt = Objects.requireNonNull(now, "now");
        return true;
    }

    public synchronized boolean complete(CheckResult completionResult, String completionComment, Instant now) {
        Objects.requireNonNull(completionResult, "completionResult");
        Objects.requireNonNull(now, "now");
        if (state.isTerminal()) {
            return false;
        }
        result = completionResult;
        comment = completionComment == null ? "" : completionComment;
        state = completionResult == CheckResult.CANCELLED ? CheckState.CANCELLED : CheckState.COMPLETED;
        endedAt = now;
        updatedAt = now;
        return true;
    }

    public synchronized CheckSessionSnapshot snapshot() {
        return new CheckSessionSnapshot(
                id,
                playerId,
                playerName,
                moderatorId,
                moderatorName,
                startedAt,
                deadlineAt,
                endedAt,
                reason,
                state,
                result,
                comment,
                origin,
                disconnected,
                disconnectedAt,
                rejoinedAt,
                updatedAt
        );
    }

    public synchronized Duration remaining(Instant now) {
        Duration remaining = Duration.between(now, deadlineAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public String id() {
        return id;
    }

    public UUID playerId() {
        return playerId;
    }

    public synchronized String playerName() {
        return playerName;
    }

    public UUID moderatorId() {
        return moderatorId;
    }

    public String moderatorName() {
        return moderatorName;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    public String reason() {
        return reason;
    }

    public synchronized CheckState state() {
        return state;
    }

    public synchronized CheckResult result() {
        return result;
    }

    public synchronized boolean isTerminal() {
        return state.isTerminal();
    }

    private void validateState() {
        if (!deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after startedAt");
        }
        if (state.isTerminal() && result == null) {
            throw new IllegalArgumentException("terminal session must have a result");
        }
        if (!state.isTerminal() && result != null) {
            throw new IllegalArgumentException("active session must not have a result");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
