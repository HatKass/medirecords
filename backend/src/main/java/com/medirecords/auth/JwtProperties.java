package com.medirecords.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings. Tokens are signed with HMAC-SHA256, so the secret must be at least 256 bits;
 * the application refuses to start with a shorter one.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(String secret, Duration ttl, String issuer) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "app.security.jwt.secret (JWT_SECRET) must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        ttl = ttl == null ? Duration.ofHours(8) : ttl;
        issuer = issuer == null || issuer.isBlank() ? "medirecords" : issuer;
    }
}
