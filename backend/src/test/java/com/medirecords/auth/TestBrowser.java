package com.medirecords.auth;

import java.net.HttpCookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Minimal browser stand-in for tests: keeps cookies from Set-Cookie headers, sends each one only to
 * paths under its Path attribute, and (like Angular) copies XSRF-TOKEN into X-XSRF-TOKEN on writes.
 */
class TestBrowser {

    private record StoredCookie(String value, String path) {}

    private final TestRestTemplate restTemplate;
    private final Map<String, StoredCookie> cookies = new LinkedHashMap<>();

    TestBrowser(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** Raw Set-Cookie header for the named cookie, or fails if the response did not set it. */
    static String setCookie(ResponseEntity<?> response, String name) {
        List<String> headers = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        return headers.stream()
                .filter(header -> header.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Set-Cookie for " + name + " in " + headers));
    }

    void fetchCsrfCookie() {
        get("/api/auth/me", String.class);
    }

    ResponseEntity<String> login(String username) {
        if (cookie("XSRF-TOKEN") == null) {
            fetchCsrfCookie();
        }
        ResponseEntity<String> response = post("/api/auth/login",
                Map.of("username", username, "password", "demo123"), String.class);
        if (response.getStatusCode() != HttpStatus.OK) {
            throw new AssertionError("Login failed: " + response);
        }
        return response;
    }

    <T> ResponseEntity<T> get(String path, Class<T> type) {
        return send(HttpMethod.GET, path, null, type, false);
    }

    <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        return send(HttpMethod.POST, path, body, type, true);
    }

    <T> ResponseEntity<T> postWithoutCsrf(String path, Object body, Class<T> type) {
        return send(HttpMethod.POST, path, body, type, false);
    }

    String cookie(String name) {
        StoredCookie cookie = cookies.get(name);
        return cookie == null ? null : cookie.value();
    }

    /** Overwrites a cookie's value, keeping the path the server gave it. */
    void setCookie(String name, String value) {
        StoredCookie existing = cookies.get(name);
        String path = existing != null ? existing.path()
                : name.equals(AuthCookies.REFRESH_COOKIE) ? AuthCookies.REFRESH_PATH : AuthCookies.ACCESS_PATH;
        cookies.put(name, new StoredCookie(value, path));
    }

    void removeCookie(String name) {
        cookies.remove(name);
    }

    private <T> ResponseEntity<T> send(HttpMethod method, String path, Object body, Class<T> type, boolean withCsrf) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String cookieHeader = cookies.entrySet().stream()
                .filter(entry -> path.startsWith(entry.getValue().path()))
                .map(entry -> entry.getKey() + "=" + entry.getValue().value())
                .collect(Collectors.joining("; "));
        if (!cookieHeader.isEmpty()) {
            headers.add(HttpHeaders.COOKIE, cookieHeader);
        }
        if (withCsrf && cookie("XSRF-TOKEN") != null) {
            headers.add("X-XSRF-TOKEN", cookie("XSRF-TOKEN"));
        }
        ResponseEntity<T> response = restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
        store(response);
        return response;
    }

    private void store(ResponseEntity<?> response) {
        for (String header : response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
            for (HttpCookie cookie : HttpCookie.parse(header)) {
                if (cookie.getMaxAge() == 0 || cookie.getValue().isEmpty()) {
                    cookies.remove(cookie.getName());
                } else {
                    cookies.put(cookie.getName(),
                            new StoredCookie(cookie.getValue(), cookie.getPath() == null ? "/" : cookie.getPath()));
                }
            }
        }
    }
}
