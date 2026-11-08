package com.medirecords.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.medirecords.TestcontainersConfiguration;
import com.medirecords.support.TestBrowser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Exercises the cookie session end to end against a real database, through a {@link TestBrowser}
 * that stores cookies by path and sends the CSRF header the way Angular does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private TestBrowser browser;

    private record UserBody(String username, String fullName, List<String> roles) {}

    private record Problem(int status, String title, String detail) {}

    private static final UserBody ADMIN = new UserBody("admin", "Dr. Alex Morgan", List.of("ADMIN"));
    private static final UserBody VIEWER = new UserBody("viewer", "Sam Taylor", List.of("VIEWER"));

    @BeforeEach
    void openBrowser() {
        browser = new TestBrowser(restTemplate);
    }

    @Test
    void firstRequestIssuesReadableCsrfCookieEvenWhenUnauthenticated() {
        ResponseEntity<Problem> response = browser.get("/api/auth/me", Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String csrfCookie = TestBrowser.setCookie(response, "XSRF-TOKEN");
        assertThat(csrfCookie).contains("Path=/").contains("SameSite=Lax").doesNotContain("HttpOnly");
        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank();
    }

    @Test
    void requestWithoutCookieIsUnauthorized() {
        ResponseEntity<Problem> response = browser.get("/api/auth/me", Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
    }

    @Test
    void writesWithoutCsrfTokenAreRejected() {
        browser.fetchCsrfCookie();

        ResponseEntity<Problem> login = browser.postWithoutCsrf("/api/auth/login", credentials("admin"), Problem.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(login.getBody()).isNotNull();
        assertThat(login.getBody().detail()).isEqualTo("Missing or invalid CSRF token.");
        assertThat(login.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE))
                .noneMatch(cookie -> cookie.startsWith(AuthCookies.ACCESS_COOKIE));

        browser.login("admin");
        ResponseEntity<Problem> write = browser.postWithoutCsrf("/api/anything", Map.of(), Problem.class);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(write.getBody()).isNotNull();
        assertThat(write.getBody().detail()).isEqualTo("Missing or invalid CSRF token.");
    }

    @Test
    void loginSetsHttpOnlyCookiesAndKeepsTokensOutOfTheBody() {
        ResponseEntity<String> response = browser.login("admin");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("accessToken").doesNotContain("eyJ");
        assertThat(TestBrowser.setCookie(response, AuthCookies.ACCESS_COOKIE))
                .contains("Path=/api;").contains("HttpOnly").contains("Secure").contains("SameSite=Lax")
                .containsPattern("Max-Age=(899|900);"); // JWT exp has whole-second precision
        assertThat(TestBrowser.setCookie(response, AuthCookies.REFRESH_COOKIE))
                .contains("Path=/api/auth/refresh").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
    }

    @Test
    void meReturnsUsernameAndRolesFromTheCookie() {
        browser.login("viewer");

        ResponseEntity<UserBody> response = browser.get("/api/auth/me", UserBody.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(VIEWER);
    }

    /** Regression: authenticated requests must not wipe the XSRF cookie, or the next write fails. */
    @Test
    void csrfCookieSurvivesAuthenticatedRequests() {
        browser.login("admin");
        String csrfBefore = browser.cookie("XSRF-TOKEN");

        ResponseEntity<String> me = browser.get("/api/auth/me", String.class);

        assertThat(me.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).noneMatch(c -> c.startsWith("XSRF-TOKEN="));
        assertThat(browser.cookie("XSRF-TOKEN")).isEqualTo(csrfBefore);
        assertThat(browser.post("/api/anything", Map.of(), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void tamperedAccessCookieIsUnauthorized() {
        browser.login("viewer");
        browser.setCookie(AuthCookies.ACCESS_COOKIE, browser.cookie(AuthCookies.ACCESS_COOKIE) + "x");

        assertThat(browser.get("/api/auth/me", Problem.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameResponse() {
        browser.fetchCsrfCookie();

        ResponseEntity<Problem> wrongPassword = browser.post("/api/auth/login",
                Map.of("username", "admin", "password", "nope"), Problem.class);
        ResponseEntity<Problem> unknownUser = browser.post("/api/auth/login", credentials("ghost"), Problem.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isNotNull();
        assertThat(wrongPassword.getBody().detail()).isEqualTo("Invalid username or password.");
        assertThat(unknownUser.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    void blankCredentialsAreRejectedAsBadRequest() {
        browser.fetchCsrfCookie();

        ResponseEntity<Problem> response = browser.post("/api/auth/login",
                Map.of("username", "", "password", ""), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refreshRotatesTokensAndDetectsReplay() {
        browser.login("admin");
        String firstRefresh = browser.cookie(AuthCookies.REFRESH_COOKIE);
        String firstAccess = browser.cookie(AuthCookies.ACCESS_COOKIE);

        // Expired access token: the browser has dropped the cookie, but refresh still works.
        browser.removeCookie(AuthCookies.ACCESS_COOKIE);
        ResponseEntity<UserBody> refreshed = browser.post("/api/auth/refresh", null, UserBody.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).isEqualTo(ADMIN);
        String secondRefresh = browser.cookie(AuthCookies.REFRESH_COOKIE);
        assertThat(secondRefresh).isNotBlank().isNotEqualTo(firstRefresh);
        assertThat(browser.cookie(AuthCookies.ACCESS_COOKIE)).isNotBlank().isNotEqualTo(firstAccess);
        assertThat(browser.get("/api/auth/me", UserBody.class).getBody()).isEqualTo(ADMIN);

        // Replaying the old (rotated) refresh token is rejected, clears the cookies...
        browser.setCookie(AuthCookies.REFRESH_COOKIE, firstRefresh);
        ResponseEntity<Problem> replay = browser.post("/api/auth/refresh", null, Problem.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().title()).isEqualTo("Session expired");
        assertThat(TestBrowser.setCookie(replay, AuthCookies.REFRESH_COOKIE)).contains("Max-Age=0");

        // ...and revokes the whole session, including the newest token.
        browser.setCookie(AuthCookies.REFRESH_COOKIE, secondRefresh);
        assertThat(browser.post("/api/auth/refresh", null, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshWithoutCookieIsUnauthorized() {
        browser.fetchCsrfCookie();

        assertThat(browser.post("/api/auth/refresh", null, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutClearsCookiesAndRevokesTheRefreshToken() {
        browser.login("admin");
        String refreshToken = browser.cookie(AuthCookies.REFRESH_COOKIE);

        ResponseEntity<Void> logout = browser.post("/api/auth/logout", null, Void.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(TestBrowser.setCookie(logout, AuthCookies.ACCESS_COOKIE)).contains("Max-Age=0").contains("Path=/api;");
        assertThat(TestBrowser.setCookie(logout, AuthCookies.REFRESH_COOKIE))
                .contains("Max-Age=0").contains("Path=/api/auth/refresh");
        assertThat(browser.cookie(AuthCookies.ACCESS_COOKIE)).isNull();
        assertThat(browser.get("/api/auth/me", Problem.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Even a copy of the refresh cookie kept from before logout is dead.
        browser.setCookie(AuthCookies.REFRESH_COOKIE, refreshToken);
        assertThat(browser.post("/api/auth/refresh", null, Problem.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Write rules are enforced before routing: a VIEWER is stopped with 403 on any write,
     * while an ADMIN passes security and reaches the dispatcher (404: no such endpoint yet).
     */
    @Test
    void onlyAdminMayCallWriteEndpoints() {
        browser.login("viewer");
        ResponseEntity<Problem> viewer = browser.post("/api/anything", Map.of(), Problem.class);

        TestBrowser adminBrowser = new TestBrowser(restTemplate);
        adminBrowser.login("admin");
        ResponseEntity<Problem> admin = adminBrowser.post("/api/anything", Map.of(), Problem.class);

        assertThat(viewer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewer.getBody()).isNotNull();
        assertThat(viewer.getBody().detail()).isEqualTo("Your role does not allow this action.");
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void healthAndApiDocsStayPublic() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, String> credentials(String username) {
        return Map.of("username", username, "password", "demo123");
    }
}
