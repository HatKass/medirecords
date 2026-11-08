package com.medirecords.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.medirecords.TestcontainersConfiguration;
import com.medirecords.support.TestBrowser;
import com.medirecords.support.TestData;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Login rate limiting over real HTTP: 5 failures per username + client address in 15 minutes (the
 * defaults), then 429. Each test uses its own client addresses (via X-Forwarded-For) and usernames,
 * so the in-memory counters of the shared application context do not leak between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LoginRateLimitIntegrationTest {

    /** Second octet picked once per run, then one new address per call: no two tests share one. */
    private static final int RUN = ThreadLocalRandom.current().nextInt(256);
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theSixthAttemptAfterFiveFailuresIsRefusedWith429EvenWithTheRightPassword() {
        String ip = randomIp();
        TestBrowser browser = browserFrom(ip);
        long lastId = lastAuditId();

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(login(browser, "admin", "wrong-" + attempt).getStatusCode()).as("attempt %d", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<JsonNode> blocked = login(browser, "admin", "demo123");
        ResponseEntity<JsonNode> blockedAgain = login(browser, "admin", "demo123");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        long retryAfter = Long.parseLong(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(850L, 900L);
        assertThat(blocked.getBody().get("title").asText()).isEqualTo("Too many sign-in attempts");
        assertThat(blocked.getBody().get("detail").asText())
                .isEqualTo("Too many failed sign-in attempts. Try again in 15 minutes.");
        assertThat(blocked.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).as("no session cookies")
                .noneMatch(cookie -> cookie.startsWith(AuthCookies.ACCESS_COOKIE + "="));
        assertThat(blockedAgain.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Five failures, then one LOGIN_BLOCKED for the block (not one per refused attempt), no sign-in.
        List<Map<String, Object>> events = jdbc.queryForList("SELECT action, details::text AS details FROM audit_log "
                + "WHERE id > ? AND actor = 'admin' AND entity_type = 'Session' ORDER BY id", lastId);
        assertThat(events).extracting(event -> event.get("action"))
                .containsExactly("LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED",
                        "LOGIN_BLOCKED");
        assertThat((String) events.getLast().get("details")).contains("\"clientIp\": \"" + ip + "\"")
                .contains("retryAfterSeconds").doesNotContain("demo123");
    }

    @Test
    void theBlockAppliesToThatUsernameFromThatAddressOnly() {
        String ip = randomIp();
        String username = "ghost" + TestData.randomLetters(6);
        failFiveTimes(browserFrom(ip), username);

        assertThat(login(browserFrom(ip), username, "again").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login(browserFrom(randomIp()), username, "again").getStatusCode())
                .as("same username, other address").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(browserFrom(ip), "viewer", "demo123").getStatusCode())
                .as("other account, same address").isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSuccessfulSignInResetsTheUsersFailures() {
        String ip = randomIp();
        TestBrowser browser = browserFrom(ip);
        for (int attempt = 0; attempt < 4; attempt++) {
            login(browser, "viewer", "nope");
        }
        assertThat(login(browser, "viewer", "demo123").getStatusCode()).isEqualTo(HttpStatus.OK);

        TestBrowser later = browserFrom(ip);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(login(later, "viewer", "nope").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(login(later, "viewer", "demo123").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void oneAddressTryingManyUsernamesIsBlocked() {
        String ip = randomIp();
        String prefix = "spray" + TestData.randomLetters(5);
        for (int user = 0; user < 20; user++) {
            assertThat(login(browserFrom(ip), prefix + user, "Winter2026!").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(login(browserFrom(ip), prefix + "-next", "Winter2026!").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void invalidRequestsAreNotCounted() {
        TestBrowser browser = browserFrom(randomIp());
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThat(login(browser, "viewer", "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        assertThat(login(browser, "viewer", "demo123").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void failFiveTimes(TestBrowser browser, String username) {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(login(browser, username, "nope").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    private ResponseEntity<JsonNode> login(TestBrowser browser, String username, String password) {
        if (browser.cookie("XSRF-TOKEN") == null) {
            browser.fetchCsrfCookie();
        }
        return browser.post("/api/auth/login", Map.of("username", username, "password", password), JsonNode.class);
    }

    private TestBrowser browserFrom(String ip) {
        return TestBrowser.from(restTemplate, ip);
    }

    /** A private address no other request in this run uses. */
    private static String randomIp() {
        int next = NEXT_ADDRESS.getAndIncrement();
        return "10." + RUN + "." + (next / 250) + "." + (next % 250 + 1);
    }

    private long lastAuditId() {
        Long id = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM audit_log", Long.class);
        return id == null ? 0 : id;
    }
}
