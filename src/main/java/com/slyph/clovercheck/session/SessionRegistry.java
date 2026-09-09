package com.slyph.clovercheck.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SessionRegistry {
    public enum RegisterResult {
        ADDED,
        PLAYER_BUSY,
        MODERATOR_BUSY,
        TERMINAL
    }

    private final Map<UUID, CheckSession> byPlayer = new LinkedHashMap<>();
    private final Map<String, CheckSession> byId = new LinkedHashMap<>();

    public synchronized RegisterResult register(CheckSession session, boolean onePerModerator) {
        if (session.isTerminal()) {
            return RegisterResult.TERMINAL;
        }
        if (byPlayer.containsKey(session.playerId())) {
            return RegisterResult.PLAYER_BUSY;
        }
        UUID moderatorId = session.moderatorId();
        if (onePerModerator && moderatorId != null && hasModeratorSession(moderatorId)) {
            return RegisterResult.MODERATOR_BUSY;
        }
        byPlayer.put(session.playerId(), session);
        byId.put(session.id().toLowerCase(Locale.ROOT), session);
        return RegisterResult.ADDED;
    }

    public synchronized boolean restore(CheckSession session) {
        return register(session, false) == RegisterResult.ADDED;
    }

    public synchronized boolean removeTerminal(CheckSession session) {
        if (!session.isTerminal()) {
            return false;
        }
        CheckSession current = byPlayer.get(session.playerId());
        if (current != session) {
            return false;
        }
        byPlayer.remove(session.playerId());
        byId.remove(session.id().toLowerCase(Locale.ROOT));
        return true;
    }

    public synchronized Optional<CheckSession> byPlayer(UUID playerId) {
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    public synchronized Optional<CheckSession> byName(String playerName) {
        return byPlayer.values().stream()
                .filter(session -> session.playerName().equalsIgnoreCase(playerName))
                .findFirst();
    }

    public synchronized Optional<CheckSession> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    public synchronized Optional<CheckSession> byModerator(UUID moderatorId) {
        List<CheckSession> sessions = forModeratorInternal(moderatorId);
        return sessions.size() == 1 ? Optional.of(sessions.getFirst()) : Optional.empty();
    }

    public synchronized List<CheckSession> forModerator(UUID moderatorId) {
        return List.copyOf(forModeratorInternal(moderatorId));
    }

    public synchronized List<CheckSession> activeSessions() {
        return List.copyOf(byPlayer.values());
    }

    public synchronized int size() {
        return byPlayer.size();
    }

    private boolean hasModeratorSession(UUID moderatorId) {
        for (CheckSession session : byPlayer.values()) {
            if (moderatorId.equals(session.moderatorId())) {
                return true;
            }
        }
        return false;
    }

    private List<CheckSession> forModeratorInternal(UUID moderatorId) {
        List<CheckSession> sessions = new ArrayList<>();
        for (CheckSession session : byPlayer.values()) {
            if (moderatorId.equals(session.moderatorId())) {
                sessions.add(session);
            }
        }
        return sessions;
    }
}
