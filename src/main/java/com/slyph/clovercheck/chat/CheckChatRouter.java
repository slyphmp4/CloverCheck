package com.slyph.clovercheck.chat;

import com.slyph.clovercheck.session.CheckSession;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CheckChatRouter {
    public enum Status {
        NONE,
        ACTIVE,
        AMBIGUOUS
    }

    public enum Role {
        CHECKED,
        MODERATOR
    }

    public record Resolution(Status status, Role role, CheckSession session) {
        public Resolution {
            Objects.requireNonNull(status, "status");
            if (status == Status.ACTIVE) {
                Objects.requireNonNull(role, "role");
                Objects.requireNonNull(session, "session");
            }
        }

        public static Resolution none() {
            return new Resolution(Status.NONE, null, null);
        }

        public static Resolution ambiguous() {
            return new Resolution(Status.AMBIGUOUS, null, null);
        }

        public static Resolution active(Role role, CheckSession session) {
            return new Resolution(Status.ACTIVE, role, session);
        }
    }

    private CheckChatRouter() {
    }

    public static Resolution resolve(List<CheckSession> sessions, UUID participantId) {
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(participantId, "participantId");

        for (CheckSession session : sessions) {
            if (!session.isTerminal() && participantId.equals(session.playerId())) {
                return Resolution.active(Role.CHECKED, session);
            }
        }

        CheckSession moderated = null;
        for (CheckSession session : sessions) {
            if (session.isTerminal() || !participantId.equals(session.moderatorId())) {
                continue;
            }
            if (moderated != null) {
                return Resolution.ambiguous();
            }
            moderated = session;
        }

        return moderated == null
                ? Resolution.none()
                : Resolution.active(Role.MODERATOR, moderated);
    }
}
