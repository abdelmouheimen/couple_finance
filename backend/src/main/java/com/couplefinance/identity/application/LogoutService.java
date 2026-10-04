package com.couplefinance.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.couplefinance.identity.domain.RefreshTokenRepository;
import com.couplefinance.identity.domain.SessionRepository;
import com.couplefinance.identity.domain.SessionRevokeReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Logout of the current session and "log out everywhere" (security.md §3). The user and the session come from the
 * validated access token ({@code sub}, {@code sid}), never from the request. Revocation uses conditional updates
 * scoped to the owner, so it is idempotent and a user can never revoke another user's session; it takes effect
 * for the refresh capability immediately (access tokens live out their 15 minutes, which is accepted).
 */
@Service
public class LogoutService {

    private static final Logger log = LoggerFactory.getLogger(LogoutService.class);

    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;
    private final TransactionTemplate transaction;

    LogoutService(SessionRepository sessions, RefreshTokenRepository refreshTokens, Clock clock,
                  PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Revokes the session of the current access token; idempotent. */
    public void logout() {
        Jwt jwt = currentJwt();
        UUID userId = uuidClaim(jwt.getSubject());
        UUID sessionId = uuidClaim(jwt.getClaimAsString("sid"));
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> {
            if (sessions.revoke(sessionId, userId, SessionRevokeReason.LOGOUT, now) > 0) {
                refreshTokens.revokeAllOfSession(sessionId, now);
            }
        });
        log.info("Logout: user={} session={}", userId, sessionId);
    }

    /** Revokes every session of the current user. */
    public void logoutAll() {
        UUID userId = uuidClaim(currentJwt().getSubject());
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> {
            sessions.revokeAllOfUser(userId, SessionRevokeReason.LOGOUT_ALL, now);
            refreshTokens.revokeAllOfUser(userId, now);
        });
        log.info("Logout everywhere: user={}", userId);
    }

    private static Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new InsufficientAuthenticationException("A bearer token is required.");
        }
        return jwt;
    }

    private static UUID uuidClaim(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BadCredentialsException("The token does not designate a session.");
        }
    }
}
