package com.medirecords.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.web.util.WebUtils;

/**
 * Reads the JWT from the HttpOnly access cookie instead of the Authorization header.
 *
 * <p>Login and refresh are skipped on purpose: they must work while the browser still holds a
 * stale or expired access cookie, which would otherwise be rejected with 401 before the endpoint runs.
 */
class CookieBearerTokenResolver implements BearerTokenResolver {

    private static final Set<String> IGNORED_PATHS = Set.of("/api/auth/login", "/api/auth/refresh");

    @Override
    public String resolve(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (IGNORED_PATHS.contains(path)) {
            return null;
        }
        Cookie cookie = WebUtils.getCookie(request, AuthCookies.ACCESS_COOKIE);
        return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
    }
}
