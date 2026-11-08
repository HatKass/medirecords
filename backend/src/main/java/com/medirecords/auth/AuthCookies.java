package com.medirecords.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Writes and clears the two session cookies. Both are HttpOnly (unreadable from JavaScript) and
 * SameSite=Lax; their paths limit where the browser sends them:
 * <ul>
 *   <li>access token: every {@code /api} call;</li>
 *   <li>refresh token: only {@code /api/auth/refresh}, so it is never exposed to other endpoints.</li>
 * </ul>
 */
@Component
public class AuthCookies {

    public static final String ACCESS_COOKIE = "mr_access";
    public static final String REFRESH_COOKIE = "mr_refresh";
    public static final String ACCESS_PATH = "/api";
    public static final String REFRESH_PATH = "/api/auth/refresh";

    private final CookieProperties properties;
    private final Clock clock;

    public AuthCookies(CookieProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    void writeSession(HttpServletResponse response, JwtTokenService.IssuedToken accessToken,
            RefreshTokenService.IssuedRefreshToken refreshToken) {
        add(response, ACCESS_COOKIE, accessToken.value(), ACCESS_PATH, untilExpiry(accessToken.expiresAt()));
        add(response, REFRESH_COOKIE, refreshToken.value(), REFRESH_PATH, untilExpiry(refreshToken.expiresAt()));
    }

    void clear(HttpServletResponse response) {
        add(response, ACCESS_COOKIE, "", ACCESS_PATH, Duration.ZERO);
        add(response, REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO);
    }

    private void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private Duration untilExpiry(Instant expiresAt) {
        return Duration.between(clock.instant(), expiresAt);
    }
}
