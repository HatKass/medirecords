package com.medirecords.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.medirecords.TestcontainersConfiguration;
import com.medirecords.support.TestBrowser;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The access rules as a matrix: every real API endpoint, called anonymously, as VIEWER and as ADMIN.
 * "Passes security" means the request reached the controller (any status but 401/403); what the
 * controller then does is covered by the feature tests. Also covers access tokens that must not be
 * accepted: expired, from another issuer, signed with another key, or not signed at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AccessRulesIntegrationTest {

    /** An id no row has: reads then answer 404, which still proves the request passed security. */
    private static final long NO_SUCH_ID = 999_999_999L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    private TestBrowser anonymous;
    private TestBrowser viewer;
    private TestBrowser admin;

    record Call(HttpMethod method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Stream<Call> readsForEveryRole() {
        return Stream.of(
                new Call(HttpMethod.GET, "/api/auth/me"),
                new Call(HttpMethod.GET, "/api/patients"),
                new Call(HttpMethod.GET, "/api/patients/" + NO_SUCH_ID),
                new Call(HttpMethod.GET, "/api/patients/cities"),
                new Call(HttpMethod.GET, "/api/imports"),
                new Call(HttpMethod.GET, "/api/imports/" + NO_SUCH_ID),
                new Call(HttpMethod.GET, "/api/imports/" + NO_SUCH_ID + "/errors"),
                new Call(HttpMethod.GET, "/api/imports/" + NO_SUCH_ID + "/errors.csv"),
                new Call(HttpMethod.GET, "/api/dashboard"));
    }

    static Stream<Call> adminOnlyReads() {
        return Stream.of(
                new Call(HttpMethod.GET, "/api/audit-log"),
                new Call(HttpMethod.GET, "/api/audit-log/actors"));
    }

    static Stream<Call> writes() {
        return Stream.of(
                new Call(HttpMethod.POST, "/api/patients"),
                new Call(HttpMethod.PUT, "/api/patients/" + NO_SUCH_ID),
                new Call(HttpMethod.DELETE, "/api/patients/" + NO_SUCH_ID),
                new Call(HttpMethod.POST, "/api/imports"));
    }

    static Stream<Call> everyProtectedCall() {
        return Stream.of(readsForEveryRole(), adminOnlyReads(), writes()).flatMap(calls -> calls);
    }

    @BeforeEach
    void openBrowsers() {
        anonymous = new TestBrowser(restTemplate);
        anonymous.fetchCsrfCookie();
        viewer = new TestBrowser(restTemplate);
        viewer.login("viewer");
        admin = new TestBrowser(restTemplate);
        admin.login("admin");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyProtectedCall")
    void anonymousCallsAreUnauthorized(Call call) {
        ResponseEntity<JsonNode> response = send(anonymous, call);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readsForEveryRole")
    void viewerPassesSecurityOnReads(Call call) {
        assertThat(send(viewer, call).getStatusCode().value()).isNotIn(401, 403);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"adminOnlyReads", "writes"})
    void viewerIsForbiddenFromAdminReadsAndEveryWrite(Call call) {
        ResponseEntity<JsonNode> response = send(viewer, call);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("detail").asText()).isEqualTo("Your role does not allow this action.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyProtectedCall")
    void adminPassesSecurityEverywhere(Call call) {
        assertThat(send(admin, call).getStatusCode().value()).isNotIn(401, 403);
    }

    /** Anything that is not explicitly opened up is denied, so a new actuator endpoint cannot leak by accident. */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/configprops", "/internal", "/"})
    void unlistedPathsAreDenied(String path) {
        assertThat(anonymous.get(path, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRefreshedViewerSessionIsStillReadOnly() {
        viewer.removeCookie(AuthCookies.ACCESS_COOKIE);

        ResponseEntity<JsonNode> refreshed = viewer.post("/api/auth/refresh", null, JsonNode.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().get("roles").toString()).isEqualTo("[\"VIEWER\"]");
        assertThat(send(viewer, new Call(HttpMethod.DELETE, "/api/patients/" + NO_SUCH_ID)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredAccessTokenIsRejected() {
        Instant issued = Instant.now().minus(1, ChronoUnit.HOURS);
        String token = sign(jwtEncoder, claims("admin", "ADMIN", jwtProperties.issuer(), issued, issued.plus(15, ChronoUnit.MINUTES)));

        assertRejected(token);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        Instant now = Instant.now();
        assertRejected(sign(jwtEncoder, claims("admin", "ADMIN", "someone-else", now, now.plus(15, ChronoUnit.MINUTES))));
    }

    /** A viewer who knows the token format still cannot promote themself without the server's key. */
    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "an-attacker-chosen-secret-of-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();

        assertRejected(sign(attacker, claims("viewer", "ADMIN", jwtProperties.issuer(), now, now.plus(15, ChronoUnit.MINUTES))));
    }

    @Test
    void unsignedTokenIsRejected() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        long exp = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();
        String header = base64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64.encodeToString(("{\"sub\":\"viewer\",\"role\":\"ADMIN\",\"iss\":\""
                + jwtProperties.issuer() + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));

        assertRejected(header + "." + payload + ".");
    }

    private void assertRejected(String accessToken) {
        TestBrowser browser = new TestBrowser(restTemplate);
        browser.fetchCsrfCookie();
        browser.setCookie(AuthCookies.ACCESS_COOKIE, accessToken);

        assertThat(browser.get("/api/auth/me", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(browser.get("/api/audit-log", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(browser, new Call(HttpMethod.DELETE, "/api/patients/" + NO_SUCH_ID)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static JwtClaimsSet claims(String subject, String role, String issuer, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(JwtClaims.ROLE, role)
                .claim(JwtClaims.FULL_NAME, "Forged")
                .claim(JwtClaims.SESSION_ID, UUID.randomUUID().toString())
                .build();
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** Writes carry a valid CSRF token, so only the role (or the missing session) decides the outcome. */
    private static ResponseEntity<JsonNode> send(TestBrowser browser, Call call) {
        Object body = call.method() == HttpMethod.GET || call.method() == HttpMethod.DELETE ? null : Map.of();
        return browser.sendWithCsrfHeader(call.method(), call.path(), body, JsonNode.class, browser.cookie("XSRF-TOKEN"));
    }
}
