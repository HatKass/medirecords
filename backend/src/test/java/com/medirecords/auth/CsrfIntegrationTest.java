package com.medirecords.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.medirecords.TestcontainersConfiguration;
import com.medirecords.support.TestBrowser;
import com.medirecords.support.TestData;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CSRF protection on the real state-changing endpoints of a signed-in ADMIN. The session cookie is
 * sent automatically by the browser, so a cross-site page could trigger these requests; only the
 * XSRF-TOKEN double submit stops it. Each case checks the 403 <em>and</em> that nothing changed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CsrfIntegrationTest {

    /** The ways a forged request can get the token wrong. */
    enum Forgery {
        /** A cross-site form or fetch: the cookie goes along, but the page cannot read it to set the header. */
        NO_HEADER,
        /** A guessed or stale token. */
        WRONG_HEADER,
        /** A header without the matching cookie (e.g. a token leaked from another browser). */
        NO_COOKIE
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private TestBrowser admin;
    private String token;

    @BeforeEach
    void setUp() {
        admin = new TestBrowser(restTemplate);
        admin.login("admin");
        token = TestData.randomLetters(6) + "cq";
    }

    @ParameterizedTest
    @EnumSource(Forgery.class)
    void createPatientIsRejected(Forgery forgery) {
        Map<String, Object> body = patient();

        ResponseEntity<JsonNode> response = admin.sendWithCsrfHeader(HttpMethod.POST, "/api/patients", body,
                JsonNode.class, forge(forgery));

        assertCsrfRejected(response);
        assertThat(patientsWithEmail(body.get("email").toString())).isZero();
    }

    @ParameterizedTest
    @EnumSource(Forgery.class)
    void updatePatientIsRejected(Forgery forgery) {
        JsonNode created = admin.post("/api/patients", patient(), JsonNode.class).getBody();
        long id = created.get("id").asLong();
        Map<String, Object> edit = patient();
        edit.put("email", created.get("email").asText());
        edit.put("city", "Changed");
        edit.put("version", created.get("version").asLong());

        ResponseEntity<JsonNode> response = admin.sendWithCsrfHeader(HttpMethod.PUT, "/api/patients/" + id, edit,
                JsonNode.class, forge(forgery));

        assertCsrfRejected(response);
        assertThat(jdbc.queryForObject("SELECT city FROM patient WHERE id = ?", String.class, id)).isEqualTo("Leeds");
    }

    @ParameterizedTest
    @EnumSource(Forgery.class)
    void deletePatientIsRejected(Forgery forgery) {
        long id = admin.post("/api/patients", patient(), JsonNode.class).getBody().get("id").asLong();

        ResponseEntity<JsonNode> response = admin.sendWithCsrfHeader(HttpMethod.DELETE, "/api/patients/" + id, null,
                JsonNode.class, forge(forgery));

        assertCsrfRejected(response);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient WHERE id = ?", Integer.class, id)).isOne();
    }

    @ParameterizedTest
    @EnumSource(Forgery.class)
    void importUploadIsRejected(Forgery forgery) {
        String fileName = "csrf-" + token + ".csv";
        byte[] csv = ("firstName,lastName,dateOfBirth,gender,email,city\nA,B,1990-01-01,F,csrf." + token
                + "@example.com,York\n").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<JsonNode> response = admin.upload("/api/imports", fileName, csv, JsonNode.class, forge(forgery));

        assertCsrfRejected(response);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient_import WHERE file_name = ?", Integer.class,
                fileName)).isZero();
    }

    /** Otherwise any site could sign the user out (a nuisance, and a step in some login-CSRF attacks). */
    @ParameterizedTest
    @EnumSource(Forgery.class)
    void logoutIsRejectedAndTheSessionSurvives(Forgery forgery) {
        String xsrf = admin.cookie("XSRF-TOKEN");

        ResponseEntity<JsonNode> response = admin.sendWithCsrfHeader(HttpMethod.POST, "/api/auth/logout", null,
                JsonNode.class, forge(forgery));

        assertCsrfRejected(response);
        admin.setCookie("XSRF-TOKEN", xsrf);
        assertThat(admin.get("/api/auth/me", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.post("/api/auth/refresh", null, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @EnumSource(Forgery.class)
    void refreshIsRejectedAndTheTokenIsNotRotated(Forgery forgery) {
        String xsrf = admin.cookie("XSRF-TOKEN");
        String refreshToken = admin.cookie(AuthCookies.REFRESH_COOKIE);

        ResponseEntity<JsonNode> response = admin.sendWithCsrfHeader(HttpMethod.POST, "/api/auth/refresh", null,
                JsonNode.class, forge(forgery));

        assertCsrfRejected(response);
        assertThat(admin.cookie(AuthCookies.REFRESH_COOKIE)).isEqualTo(refreshToken);
        admin.setCookie("XSRF-TOKEN", xsrf);
        assertThat(admin.post("/api/auth/refresh", null, JsonNode.class).getStatusCode())
                .as("the untouched refresh token still works").isEqualTo(HttpStatus.OK);
    }

    /** Returns the header value to send, and drops the cookie for {@link Forgery#NO_COOKIE}. */
    private String forge(Forgery forgery) {
        String real = admin.cookie("XSRF-TOKEN");
        assertThat(real).isNotBlank();
        return switch (forgery) {
            case NO_HEADER -> null;
            case WRONG_HEADER -> "forged-" + token;
            case NO_COOKIE -> {
                admin.removeCookie("XSRF-TOKEN");
                yield real;
            }
        };
    }

    private static void assertCsrfRejected(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("detail").asText()).isEqualTo("Missing or invalid CSRF token.");
    }

    private int patientsWithEmail(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM patient WHERE email = ?", Integer.class, email);
    }

    private Map<String, Object> patient() {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Csrf");
        body.put("lastName", "Test" + token);
        body.put("dateOfBirth", "1980-01-01");
        body.put("gender", "MALE");
        body.put("email", "csrf." + token + "." + TestData.randomLetters(4) + "@example.com");
        body.put("city", "Leeds");
        return body;
    }
}
