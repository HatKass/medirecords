package com.medirecords.audit;

public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    /** A whole CSV import, recorded once with its counts rather than once per imported row. */
    IMPORT,
    /** Authentication events record only the username and the time. */
    LOGIN_SUCCESS,
    /** The actor is the username that was typed, which may not be an existing account. */
    LOGIN_FAILED,
    LOGOUT;

    public boolean isAuthentication() {
        return this == LOGIN_SUCCESS || this == LOGIN_FAILED || this == LOGOUT;
    }
}
