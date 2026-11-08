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
    /**
     * Sign-in refused by the rate limiter, recorded once per block (not for every refused attempt);
     * details hold the client address and the wait.
     */
    LOGIN_BLOCKED,
    LOGOUT;

    public boolean isAuthentication() {
        return this == LOGIN_SUCCESS || this == LOGIN_FAILED || this == LOGIN_BLOCKED || this == LOGOUT;
    }
}
