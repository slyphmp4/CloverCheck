package com.slyph.clovercheck.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CheckSession(
        String id,
        UUID playerId,
        String playerName,
        UUID staffId,
        String staffName,
        Instant startedAt,
        Instant deadlineAt
) {
    public CheckSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(staffName, "staffName");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (!deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after startedAt");
        }
    }

    public Duration totalDuration() {
        return Duration.between(startedAt, deadlineAt);
    }

    public Duration remaining(Instant now) {
        Duration remaining = Duration.between(now, deadlineAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public long remainingSeconds(Instant now) {
        return remaining(now).toSeconds();
    }
}
