package com.slyph.clovercheck.session;

import com.slyph.clovercheck.model.CheckResult;
import com.slyph.clovercheck.model.CheckState;
import com.slyph.clovercheck.model.StoredLocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CheckSessionSnapshot(
        String id,
        UUID playerId,
        String playerName,
        UUID moderatorId,
        String moderatorName,
        Instant startedAt,
        Instant deadlineAt,
        Instant endedAt,
        String reason,
        CheckState state,
        CheckResult result,
        String comment,
        StoredLocation origin,
        boolean disconnected,
        Instant disconnectedAt,
        Instant rejoinedAt,
        Instant updatedAt
) {
    public CheckSessionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(moderatorName, "moderatorName");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(comment, "comment");
        Objects.requireNonNull(updatedAt, "updatedAt");
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
}
