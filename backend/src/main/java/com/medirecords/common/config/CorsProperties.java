package com.medirecords.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins allowed to call the API from a browser. Only needed when the frontend is served
 * from a different origin than the backend (e.g. Vercel + Render). In Docker Compose and
 * local development the frontend proxies requests, so the list can stay empty.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
