package com.slyph.clovercheck.model;

public enum CheckOutcome {
    CLEAN(null),
    CHEATS("cheats"),
    REFUSAL("refusal"),
    ADMITTED("admitted"),
    TIMEOUT("timeout"),
    DISCONNECTED("disconnected"),
    CANCELLED(null);

    private final String actionKey;

    CheckOutcome(String actionKey) {
        this.actionKey = actionKey;
    }

    public String actionKey() {
        return actionKey;
    }
}
