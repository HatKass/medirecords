package com.medirecords.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String COOKIE_SCHEME = "accessCookie";

    /**
     * Documents cookie authentication. Swagger UI is served from the same origin as the API, so after
     * "POST /api/auth/login" the browser stores the HttpOnly cookie and sends it on every later call;
     * Swagger UI also echoes the XSRF-TOKEN cookie as a header (springdoc.swagger-ui.csrf.enabled).
     */
    @Bean
    public OpenAPI mediRecordsOpenApi(@Value("${app.version:dev}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("MediRecords API")
                        .description("Patient records management: CRUD, bulk CSV import and audit log. "
                                + "Call POST /api/auth/login first (e.g. admin / demo123); the session cookie "
                                + "is then sent automatically.")
                        .version(version))
                .components(new Components().addSecuritySchemes(COOKIE_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("mr_access")))
                .addSecurityItem(new SecurityRequirement().addList(COOKIE_SCHEME));
    }
}
