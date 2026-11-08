package com.medirecords.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cookie attributes shared by the auth and CSRF cookies. {@code secure} must stay true whenever the
 * app is served over HTTPS; it can be switched off only for plain-HTTP local development.
 */
@ConfigurationProperties(prefix = "app.security.cookie")
public record CookieProperties(Boolean secure) {

    public CookieProperties {
        secure = secure == null || secure;
    }
}
