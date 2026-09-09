package com.slyph.clovercheck.storage;

import com.slyph.clovercheck.model.AuditEventType;
import com.slyph.clovercheck.session.CheckSessionSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface CheckRepository {
    CompletableFuture<List<CheckSessionSnapshot>> initializeAndLoadActive();

    CompletableFuture<Void> upsert(CheckSessionSnapshot snapshot);

    CompletableFuture<Void> appendAudit(
            String sessionId,
            AuditEventType type,
            UUID actorId,
            String actorName,
            Instant timestamp,
            String details
    );

    CompletableFuture<List<CheckSessionSnapshot>> history(String playerQuery, int limit);

    CompletableFuture<Optional<CheckSessionSnapshot>> findById(String id);

    void shutdown(Collection<CheckSessionSnapshot> activeSessions, Duration timeout);
}
