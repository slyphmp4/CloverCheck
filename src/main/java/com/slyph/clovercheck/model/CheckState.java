package com.slyph.clovercheck.model;

public enum CheckState {
    STARTING,
    ACTIVE,
    DISCONNECTED,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
