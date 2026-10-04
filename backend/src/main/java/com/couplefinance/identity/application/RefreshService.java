package com.couplefinance.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.identity.domain.RefreshPolicy;
import com.couplefinance.identity.domain.RefreshPolicy.Decision;
import com.couplefinance.identity.domain.RefreshToken;
import com.couplefinance.identity.domain.RefreshTokenRef;
import com.couplefinance.identity.domain.RefreshTokenRepository;
import com.couplefinance.identity.domain.Session;
import com.couplefinance.identity.domain.SessionRepository;
import com.couplefinance.identity.domain.SessionRevokeReason;
import com.couplefinance.identity.domain.UserAccountRepository;
import com.couplefinance.identity.domain.UserAccountStatus;
import com.couplefinance.identity.infrastructure.JwtProperties;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UuidV7;
import com.couplefinance.shared.ratelimit.RateLimitExceededException;
import com.couplefinance.shared.ratelimit.RateLimiter;
import com.couplefinance.shared.ratelimit.RateLimiter.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh-token rotation with reuse detection (security.md §3, ADR-007 option A).
 *
 * <p>Every refresh of a session is serialised by a {@code SELECT ... FOR UPDATE} on the session row, and the state
 * of the presented token is re-read under that lock: parallel presentations are therefore applied one after the
 * other, always rotating the newest token of the single chain forward. A token rotated less than the grace window
 * ago is a legitimate parallel refresh (the chain head rotates again, the session is not revoked); an older rotated
 * token is a replay and revokes the session. Unknown, expired, revoked and replayed tokens produce the same error.
 * No external call happens inside the transaction; the access token is signed afterwards.
 */
@Service
public class RefreshService {

    /** Rate-limit profile of the refresh route ({@code couplefinance.rate-limit.profiles.auth-refresh}). */
    public static final String RATE_LIMIT_PROFILE = "auth-refresh";

    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);

    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final UserAccountRepository accounts;
    private final AccessTokenIssuer accessTokens;
    private final JwtProperties jwtProperties;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final TransactionTemplate transaction;

    RefreshService(SessionRepository sessions, RefreshTokenRepository refreshTokens, UserAccountRepository accounts,
                   AccessTokenIssuer accessTokens, JwtProperties jwtProperties, RateLimiter rateLimiter,
                   Clock clock, PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.accessTokens = accessTokens;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The outcome of the locked section: tokens to return, or a refusal. */
    private record Outcome(UUID userId, UUID sessionId, String refreshSecret) {
        static Outcome refused() {
            return new Outcome(null, null, null);
        }

        boolean accepted() {
            return refreshSecret != null;
        }
    }

    public LoginResult refresh(String presentedSecret) {
        RefreshTokenRef ref = refreshTokens.findRefByHash(RefreshTokenSecrets.hash(presentedSecret))
                .orElseThrow(RefreshService::invalidRefreshToken);
        enforceSessionRateLimit(ref.sessionId());

        Outcome outcome = transaction.execute(status -> rotate(ref));
        if (outcome == null || !outcome.accepted()) {
            throw invalidRefreshToken();
        }
        return new LoginResult(accessTokens.issue(outcome.userId(), outcome.sessionId()), outcome.refreshSecret(),
                accessTokens.ttl().toSeconds());
    }

    private Outcome rotate(RefreshTokenRef ref) {
        Instant now = clock.instant();
        Optional<Session> locked = sessions.findByIdForUpdate(ref.sessionId());
        Optional<RefreshToken> presented = refreshTokens.findById(ref.id());
        if (locked.isEmpty() || presented.isEmpty()) {
            return Outcome.refused();
        }
        Session session = locked.get();
        boolean accountUsable = accounts.findById(session.userId())
                .filter(account -> account.status() != UserAccountStatus.DELETED).isPresent();
        if (!accountUsable) {
            return Outcome.refused();
        }

        Decision decision = RefreshPolicy.decide(session, presented.get(), now);
        switch (decision) {
            case REJECT:
                return Outcome.refused();
            case REUSE_DETECTED:
                session.revoke(SessionRevokeReason.REUSE_DETECTED, now);
                refreshTokens.revokeAllOfSession(session.id(), now);
                log.warn("Refresh token reuse outside the grace window, session revoked: session={}", session.id());
                return Outcome.refused();
            case ROTATE, ROTATE_FORWARD:
                RefreshToken head = decision == Decision.ROTATE ? presented.get() : chainHead(presented.get());
                if (head.isRevoked() || head.isExpired(now)) {
                    return Outcome.refused();
                }
                String secret = RefreshTokenSecrets.generate();
                UUID successorId = UuidV7.generate(clock);
                refreshTokens.save(new RefreshToken(successorId, session.id(), RefreshTokenSecrets.hash(secret), now,
                        now.plus(jwtProperties.refreshTokenTtl())));
                head.rotateTo(successorId, now);
                session.touch(now);
                return new Outcome(session.userId(), session.id(), secret);
            default:
                throw new IllegalStateException("Unhandled decision " + decision);
        }
    }

    private RefreshToken chainHead(RefreshToken token) {
        RefreshToken current = token;
        while (current.isRotated()) {
            current = refreshTokens.findById(current.replacedById())
                    .orElseThrow(() -> new IllegalStateException("Broken refresh-token chain."));
        }
        return current;
    }

    private void enforceSessionRateLimit(UUID sessionId) {
        Optional<Long> retryAfter = rateLimiter.tryConsume(Stage.USER, RATE_LIMIT_PROFILE, "refresh:" + sessionId);
        if (retryAfter.isPresent()) {
            throw new RateLimitExceededException(retryAfter.get());
        }
    }

    private static ApplicationException invalidRefreshToken() {
        return new ApplicationException(IdentityErrorCode.INVALID_REFRESH_TOKEN, "The refresh token is not valid.");
    }
}
