package com.medirecords.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How long a session can be kept alive by refreshing without signing in again. */
@ConfigurationProperties(prefix = "app.security.refresh-token")
public record RefreshTokenProperties(Duration ttl) {

    public RefreshTokenProperties {
        ttl = ttl == null ? Duration.ofDays(7) : ttl;
    }
}
