package com.medirecords.auth;

/** ADMIN may read and change everything; VIEWER is read-only. */
public enum Role {
    ADMIN,
    VIEWER;

    /** Spring Security authority name, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
