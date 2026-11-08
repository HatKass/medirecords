package com.medirecords.auth;

import com.medirecords.audit.AuditAction;
import com.medirecords.audit.AuditService;
import com.medirecords.auth.AuthDtos.CurrentUser;
import com.medirecords.auth.AuthDtos.LoginRequest;
import com.medirecords.auth.RefreshTokenService.IssuedRefreshToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cookie-based session endpoints. All POSTs here are CSRF-protected like every other write;
 * the SPA receives the XSRF-TOKEN cookie from its first GET.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final AuthCookies cookies;
    private final AuditService audit;
    private final LoginRateLimiter rateLimiter;

    public AuthController(AuthenticationManager authenticationManager, JwtTokenService accessTokens,
            RefreshTokenService refreshTokens, AuthCookies cookies, AuditService audit,
            LoginRateLimiter rateLimiter) {
        this.authenticationManager = authenticationManager;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    @SecurityRequirements // public endpoint
    @Operation(summary = "Sign in: sets the HttpOnly access and refresh cookies and returns the user",
            description = "After too many failed attempts (by default 5 per username and client address in "
                    + "15 minutes) answers 429 with a Retry-After header, without checking the password.")
    public CurrentUser login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        // The client address as resolved by the forwarded-header support (X-Forwarded-For behind a proxy).
        String clientIp = httpRequest.getRemoteAddr();
        rateLimiter.check(request.username(), clientIp).ifPresent(rejection -> {
            if (rejection.firstOfBlock()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("clientIp", clientIp);
                details.put("retryAfterSeconds", retryAfterSeconds(rejection.retryAfter()));
                audit.recordAuthentication(AuditAction.LOGIN_BLOCKED, request.username(), details);
            }
            throw new TooManyLoginAttemptsException(rejection.retryAfter());
        });

        AuthenticatedUser user;
        try {
            user = (AuthenticatedUser) authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()))
                    .getPrincipal();
        } catch (AuthenticationException exception) {
            rateLimiter.recordFailure(request.username(), clientIp);
            // Only the typed username is audited; the password never leaves this method.
            audit.recordAuthentication(AuditAction.LOGIN_FAILED, request.username());
            throw exception; // GlobalExceptionHandler answers 401 with a generic message
        }
        rateLimiter.recordSuccess(request.username(), clientIp);
        IssuedRefreshToken refreshToken = refreshTokens.startSession(user.getUserId());
        cookies.writeSession(response, accessTokens.issue(user, refreshToken.familyId()), refreshToken);
        audit.recordAuthentication(AuditAction.LOGIN_SUCCESS, user.getUsername());
        return toCurrentUser(user);
    }

    @PostMapping("/refresh")
    @SecurityRequirements // authenticated by the refresh cookie, not the access token
    @Operation(summary = "Rotate the refresh cookie and issue a fresh access cookie")
    public CurrentUser refresh(@CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken)
                .orElseThrow(InvalidRefreshTokenException::new);
        AuthenticatedUser user = rotation.user();
        cookies.writeSession(response, accessTokens.issue(user, rotation.refreshToken().familyId()),
                rotation.refreshToken());
        return toCurrentUser(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out: revokes this session's refresh tokens and clears both cookies")
    public void logout(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        if (jwt != null && jwt.getClaimAsString(JwtClaims.SESSION_ID) != null) {
            refreshTokens.revokeSession(UUID.fromString(jwt.getClaimAsString(JwtClaims.SESSION_ID)));
        }
        cookies.clear(response);
        // Without a valid access cookie we cannot tell who is signing out, so there is nothing to attribute.
        if (jwt != null) {
            audit.recordAuthentication(AuditAction.LOGOUT, jwt.getSubject());
        }
    }

    @GetMapping("/me")
    @Operation(summary = "Return the signed-in user (used by the SPA to restore its session on start)")
    public CurrentUser me(@AuthenticationPrincipal Jwt jwt) {
        return new CurrentUser(jwt.getSubject(), jwt.getClaimAsString(JwtClaims.FULL_NAME),
                List.of(Role.valueOf(jwt.getClaimAsString(JwtClaims.ROLE))));
    }

    /** A dead refresh token ends the session: clear the cookies so the browser stops sending them. */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    ProblemDetail handleInvalidRefreshToken(HttpServletResponse response) {
        cookies.clear(response);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Your session has expired. Please sign in again.");
        problem.setTitle("Session expired");
        return problem;
    }

    /** 429 with Retry-After. The message does not say which limit was hit or whether the account exists. */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException exception) {
        long seconds = retryAfterSeconds(exception.retryAfter);
        long minutes = Math.max(1, (seconds + 59) / 60);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed sign-in attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."));
        problem.setTitle("Too many sign-in attempts");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(problem);
    }

    /** Whole seconds, rounded up, so a client that waits exactly this long is let in. */
    private static long retryAfterSeconds(Duration retryAfter) {
        return Math.max(1, retryAfter.plusMillis(999).toSeconds());
    }

    private static CurrentUser toCurrentUser(AuthenticatedUser user) {
        return new CurrentUser(user.getUsername(), user.getFullName(), List.of(user.getRole()));
    }

    static class TooManyLoginAttemptsException extends RuntimeException {
        private final transient Duration retryAfter;

        TooManyLoginAttemptsException(Duration retryAfter) {
            super("Too many failed sign-in attempts");
            this.retryAfter = retryAfter;
        }
    }

    static class InvalidRefreshTokenException extends RuntimeException {
        InvalidRefreshTokenException() {
            super("Refresh token missing, unknown, expired or already used");
        }
    }
}
