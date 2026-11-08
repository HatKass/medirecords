package com.medirecords.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Counts failed sign-ins in memory over a sliding window and refuses further attempts once a limit
 * is reached (see {@link LoginRateLimitProperties} for the three limits).
 *
 * <p>A refused attempt is rejected <em>before</em> the password is checked, so a blocked client
 * learns nothing, not even when it finally guesses right. A successful sign-in clears only its own
 * username + address counter; the wider counters keep counting an attacker's failures.
 *
 * <p>State lives in this process: it resets on restart and is not shared between instances, which
 * matches the single-instance deployment. Methods are synchronized; sign-ins are rare enough that a
 * single lock costs nothing.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** Upper bound on tracked counters, so a flood of made-up usernames cannot exhaust memory. */
    static final int MAX_TRACKED_KEYS = 50_000;

    private enum Scope { USERNAME_AND_IP, IP, USERNAME }

    private record Key(Scope scope, String value) {}

    /** Failure times in the window, oldest first, at most the scope's limit. */
    private static final class Counter {
        final Deque<Instant> failures = new ArrayDeque<>();
        /** Whether the current block was already reported, so a client hammering away is audited once. */
        boolean blockReported;
    }

    /**
     * A refused attempt. {@code firstOfBlock} is true for the first refusal since the limit was reached,
     * which is the one worth an audit entry.
     */
    public record Rejection(Duration retryAfter, boolean firstOfBlock) {}

    private final LoginRateLimitProperties properties;
    private final Clock clock;
    private final Map<Key, Counter> counters = new HashMap<>();

    public LoginRateLimiter(LoginRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Empty when the attempt may go ahead; otherwise how long the client has to wait. */
    public synchronized Optional<Rejection> check(String username, String clientIp) {
        Instant now = clock.instant();
        Duration retryAfter = Duration.ZERO;
        boolean firstOfBlock = false;
        for (Key key : keys(username, clientIp)) {
            Counter counter = counters.get(key);
            if (counter == null) {
                continue;
            }
            prune(counter, now);
            if (counter.failures.size() >= limit(key.scope())) {
                Duration wait = Duration.between(now, counter.failures.peekFirst().plus(properties.window()));
                retryAfter = wait.compareTo(retryAfter) > 0 ? wait : retryAfter;
                firstOfBlock |= !counter.blockReported;
                counter.blockReported = true;
            }
        }
        return retryAfter.isPositive() ? Optional.of(new Rejection(retryAfter, firstOfBlock)) : Optional.empty();
    }

    public synchronized void recordFailure(String username, String clientIp) {
        Instant now = clock.instant();
        for (Key key : keys(username, clientIp)) {
            Counter counter = counters.get(key);
            if (counter == null) {
                if (counters.size() >= MAX_TRACKED_KEYS) {
                    purgeExpired();
                }
                if (counters.size() >= MAX_TRACKED_KEYS) {
                    log.warn("Login rate limiter is tracking {} keys; not tracking {}", counters.size(), key.scope());
                    continue;
                }
                counter = new Counter();
                counters.put(key, counter);
            }
            prune(counter, now);
            counter.failures.addLast(now);
            while (counter.failures.size() > limit(key.scope())) {
                counter.failures.removeFirst();
            }
            counter.blockReported = false; // only reachable while not blocked, so any block after this is new
        }
    }

    /** The person got their password right: forget their own mistakes (but not the wider counters). */
    public synchronized void recordSuccess(String username, String clientIp) {
        counters.remove(new Key(Scope.USERNAME_AND_IP, normalize(username) + "|" + clientIp));
    }

    /** Drops counters whose failures have all left the window. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public synchronized void purgeExpired() {
        Instant now = clock.instant();
        counters.values().removeIf(counter -> {
            prune(counter, now);
            return counter.failures.isEmpty();
        });
    }

    private void prune(Counter counter, Instant now) {
        Instant cutoff = now.minus(properties.window());
        while (!counter.failures.isEmpty() && !counter.failures.peekFirst().isAfter(cutoff)) {
            counter.failures.removeFirst();
        }
    }

    private int limit(Scope scope) {
        return switch (scope) {
            case USERNAME_AND_IP -> properties.maxFailuresPerUsernameAndIp();
            case IP -> properties.maxFailuresPerIp();
            case USERNAME -> properties.maxFailuresPerUsername();
        };
    }

    private static List<Key> keys(String username, String clientIp) {
        String user = normalize(username);
        return List.of(new Key(Scope.USERNAME_AND_IP, user + "|" + clientIp), new Key(Scope.IP, clientIp),
                new Key(Scope.USERNAME, user));
    }

    /** "Admin " and "admin" are the same target. */
    private static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
