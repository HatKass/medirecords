package com.medirecords.auth;

/** Custom claim names shared by the token issuer and the token-to-authorities converter. */
final class JwtClaims {

    static final String ROLE = "role";
    static final String FULL_NAME = "name";
    /** Refresh-token family the access token belongs to; lets logout end exactly this session. */
    static final String SESSION_ID = "sid";

    private JwtClaims() {
    }
}
