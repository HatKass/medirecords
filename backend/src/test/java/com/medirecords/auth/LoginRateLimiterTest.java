package com.medirecords.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.medirecords.auth.LoginRateLimiter.Rejection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The sliding-window rules of the login rate limiter, with a clock the test moves by hand. */
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.10";
    private static final String OTHER_IP = "203.0.113.20";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T10:00:00Z"));
    private final LoginRateLimiter limiter = new LoginRateLimiter(
            new LoginRateLimitProperties(Duration.ofMinutes(15), 5, 20, 50), clock);

    @Test
    void allowsFourFailuresAndBlocksAfterTheFifth() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.check("alice", IP)).as("attempt %d", attempt).isEmpty();
            limiter.recordFailure("alice", IP);
            clock.advance(Duration.ofMinutes(1));
        }

        // First failure at 10:00, now 10:05: blocked until 10:15.
        assertThat(limiter.check("alice", IP)).hasValue(new Rejection(Duration.ofMinutes(10), true));
    }

    @Test
    void theWindowSlidesSoTheOldestFailureExpiresFirst() {
        failTimes("alice", IP, 5);                 // all at 10:00
        clock.advance(Duration.ofMinutes(15));     // 10:15: those failures leave the window

        assertThat(limiter.check("alice", IP)).isEmpty();
    }

    @Test
    void aBlockIsReportedOnceUntilTheNextBlock() {
        failTimes("alice", IP, 5);

        assertThat(limiter.check("alice", IP)).hasValueSatisfying(rejection -> assertThat(rejection.firstOfBlock()).isTrue());
        assertThat(limiter.check("alice", IP)).hasValueSatisfying(rejection -> assertThat(rejection.firstOfBlock()).isFalse());
        assertThat(limiter.check("alice", IP)).hasValueSatisfying(rejection -> assertThat(rejection.firstOfBlock()).isFalse());

        clock.advance(Duration.ofMinutes(15));
        limiter.recordFailure("alice", IP);
        failTimes("alice", IP, 4);
        assertThat(limiter.check("alice", IP)).hasValueSatisfying(rejection -> assertThat(rejection.firstOfBlock()).isTrue());
    }

    @Test
    void usernameAndAddressAreCountedTogether() {
        failTimes("alice", IP, 5);

        assertThat(limiter.check("alice", OTHER_IP)).as("same user elsewhere").isEmpty();
        assertThat(limiter.check("bob", IP)).as("other user, same address").isEmpty();
        assertThat(limiter.check(" ALICE ", IP)).as("usernames are compared trimmed, ignoring case").isPresent();
    }

    @Test
    void successClearsOnlyTheUsersOwnCounter() {
        failTimes("alice", IP, 4);
        limiter.recordSuccess("alice", IP);
        failTimes("alice", IP, 4);

        assertThat(limiter.check("alice", IP)).isEmpty();
    }

    @Test
    void oneAddressTryingManyUsernamesIsBlocked() {
        for (int user = 0; user < 20; user++) {
            limiter.recordFailure("user" + user, IP);
        }

        assertThat(limiter.check("someone-new", IP)).isPresent();
        assertThat(limiter.check("someone-new", OTHER_IP)).isEmpty();
    }

    @Test
    void oneUsernameAttackedFromManyAddressesIsBlocked() {
        for (int address = 0; address < 50; address++) {
            limiter.recordFailure("alice", "198.51.100." + address);
        }

        assertThat(limiter.check("alice", "192.0.2.1")).isPresent();
        assertThat(limiter.check("bob", "192.0.2.1")).isEmpty();
    }

    @Test
    void purgeDropsOnlyExpiredCounters() {
        failTimes("alice", IP, 5);
        clock.advance(Duration.ofMinutes(10));
        failTimes("bob", OTHER_IP, 5);
        clock.advance(Duration.ofMinutes(6)); // alice's failures expired, bob's have not

        limiter.purgeExpired();

        assertThat(limiter.check("alice", IP)).isEmpty();
        assertThat(limiter.check("bob", OTHER_IP)).isPresent();
    }

    private void failTimes(String username, String ip, int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            limiter.recordFailure(username, ip);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
