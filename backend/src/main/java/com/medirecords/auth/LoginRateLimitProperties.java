package com.medirecords.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits on failed sign-ins within a sliding {@code window}. Three counters are kept:
 *
 * <ul>
 *   <li>{@code maxFailuresPerUsernameAndIp}: the everyday rule (one person mistyping, or one client
 *       guessing one account). Keyed by both, so nobody can lock a user out from somewhere else.</li>
 *   <li>{@code maxFailuresPerIp}: one client trying many usernames (password spraying).</li>
 *   <li>{@code maxFailuresPerUsername}: one account attacked from many addresses, including clients
 *       that forge {@code X-Forwarded-For}. Higher, because reaching it locks out the real user too.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.security.login-rate-limit")
public record LoginRateLimitProperties(Duration window, int maxFailuresPerUsernameAndIp, int maxFailuresPerIp,
        int maxFailuresPerUsername) {

    public LoginRateLimitProperties {
        window = window == null ? Duration.ofMinutes(15) : window;
        maxFailuresPerUsernameAndIp = positiveOr(maxFailuresPerUsernameAndIp, 5);
        maxFailuresPerIp = positiveOr(maxFailuresPerIp, 20);
        maxFailuresPerUsername = positiveOr(maxFailuresPerUsername, 50);
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
