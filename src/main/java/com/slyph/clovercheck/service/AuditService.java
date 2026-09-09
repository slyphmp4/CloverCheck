package com.slyph.clovercheck.service;

import com.slyph.clovercheck.model.AuditEventType;
import com.slyph.clovercheck.storage.CheckRepository;

import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuditService {
    private final CheckRepository repository;
    private final Logger logger;

    public AuditService(CheckRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    public void log(String sessionId, AuditEventType type, UUID actorId, String actorName, String details) {
        String safeActor = sanitize(actorName);
        String safeDetails = sanitize(details);
        logger.info("[AUDIT] event=" + type.name() + " session=" + sessionId + " actor=" + safeActor + " details=" + safeDetails);
        repository.appendAudit(sessionId, type, actorId, safeActor, Instant.now(), safeDetails)
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Failed to persist CloverCheck audit event " + type.name() + " for " + sessionId, error);
                    return null;
                });
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    }
}
