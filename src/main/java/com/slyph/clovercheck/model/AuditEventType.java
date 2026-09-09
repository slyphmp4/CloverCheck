package com.slyph.clovercheck.model;

public enum AuditEventType {
    START,
    PLAYER_QUIT,
    PLAYER_REJOIN,
    CONFESS_REQUEST,
    CONFESS_CONFIRM,
    SESSION_COMPLETE,
    SESSION_CANCEL,
    TIMEOUT,
    STAFF_ACTION,
    COMMAND_EXECUTION,
    INTERNAL_ERROR
}
