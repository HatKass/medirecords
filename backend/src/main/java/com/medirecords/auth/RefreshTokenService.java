package com.medirecords.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>Tokens are 256 random bits, so a fast SHA-256 hash is enough to protect them at rest
 * (slow password hashes exist for low-entropy secrets) and it allows lookup by hash.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokenRepository;
    private final AppUserRepository userRepository;
    private final RefreshTokenProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokenRepository, AppUserRepository userRepository,
            RefreshTokenProperties properties, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new session (token family) after a successful login. */
    @Transactional
    public IssuedRefreshToken startSession(long userId) {
        return issue(userRepository.getReferenceById(userId), UUID.randomUUID());
    }

    /**
     * Exchanges a valid refresh token for a new one in the same family. Returns empty when the
     * token is unknown, expired, already used, or its user is disabled. Re-use of an already
     * rotated token means it was copied, so the whole family is revoked.
     */
    @Transactional
    public Optional<Rotation> rotate(String rawToken) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = tokenRepository.findByTokenHash(hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken token = found.get();
        if (token.isRevoked()) {
            log.warn("Refresh token re-use detected; revoking session family {}", token.getFamilyId());
            tokenRepository.revokeFamily(token.getFamilyId(), now);
            return Optional.empty();
        }
        AppUser user = token.getUser();
        if (token.isExpired(now) || !user.isEnabled()) {
            tokenRepository.revokeFamily(token.getFamilyId(), now);
            return Optional.empty();
        }
        token.revoke(now);
        IssuedRefreshToken next = issue(user, token.getFamilyId());
        return Optional.of(new Rotation(new AuthenticatedUser(user), next));
    }

    /** Ends a session: no token of that family can be used any more. */
    @Transactional
    public void revokeSession(UUID familyId) {
        tokenRepository.revokeFamily(familyId, clock.instant());
    }

    /** Housekeeping: expired rows have no further use (re-use detection only matters while valid). */
    @Scheduled(cron = "${app.security.refresh-token.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void deleteExpiredTokens() {
        int deleted = tokenRepository.deleteExpiredBefore(clock.instant());
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }

    private IssuedRefreshToken issue(AppUser user, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.ttl());
        tokenRepository.save(new RefreshToken(user, familyId, hash(rawToken), now, expiresAt));
        return new IssuedRefreshToken(rawToken, familyId, expiresAt);
    }

    private static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    public record IssuedRefreshToken(String value, UUID familyId, Instant expiresAt) {}

    public record Rotation(AuthenticatedUser user, IssuedRefreshToken refreshToken) {}
}
