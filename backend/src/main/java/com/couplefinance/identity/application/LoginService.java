package com.couplefinance.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.identity.domain.RefreshToken;
import com.couplefinance.identity.domain.RefreshTokenRepository;
import com.couplefinance.identity.domain.Session;
import com.couplefinance.identity.domain.SessionRepository;
import com.couplefinance.identity.domain.UserAccount;
import com.couplefinance.identity.domain.UserAccountRepository;
import com.couplefinance.identity.infrastructure.JwtProperties;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UuidV7;
import com.couplefinance.shared.ratelimit.RateLimitExceededException;
import com.couplefinance.shared.ratelimit.RateLimiter;
import com.couplefinance.shared.ratelimit.RateLimiter.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email + password login (security.md §3). Creates a session and its first refresh token, and issues an access
 * token. {@code PENDING_VERIFICATION} accounts receive tokens (protected endpoints then answer
 * {@code EMAIL_NOT_VERIFIED}); {@code DELETED} accounts cannot log in.
 *
 * <p>Unknown email, wrong password and deleted account are indistinguishable: the same error, and one Argon2id
 * verification in every case (a dummy hash stands in for a missing account). The password check runs outside any
 * database transaction.
 */
@Service
public class LoginService {

    /** Rate-limit profile of the authentication routes ({@code couplefinance.rate-limit.profiles.auth}). */
    public static final String RATE_LIMIT_PROFILE = "auth";

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserAccountRepository accounts;
    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final JwtProperties jwtProperties;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String dummyHash;

    LoginService(UserAccountRepository accounts, SessionRepository sessions, RefreshTokenRepository refreshTokens,
                 PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokens, JwtProperties jwtProperties,
                 RateLimiter rateLimiter, Clock clock, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResult login(LoginCommand command) {
        String email = command.email().strip().toLowerCase(Locale.ROOT);
        enforceAccountRateLimit(email);

        Optional<UserAccount> candidate = accounts.findLoginCandidateByLowerEmail(email);
        String hash = candidate.map(UserAccount::passwordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(command.password(), hash);
        if (candidate.isEmpty() || !passwordMatches) {
            throw new ApplicationException(IdentityErrorCode.INVALID_CREDENTIALS, "Invalid email or password.");
        }
        UserAccount account = candidate.get();

        Instant now = clock.instant();
        String refreshSecret = RefreshTokenSecrets.generate();
        UUID sessionId = UuidV7.generate(clock);
        transaction.executeWithoutResult(status -> {
            sessions.save(new Session(sessionId, account.id(), command.deviceLabel(), now));
            refreshTokens.save(new RefreshToken(UuidV7.generate(clock), sessionId,
                    RefreshTokenSecrets.hash(refreshSecret), now, now.plus(jwtProperties.refreshTokenTtl())));
        });
        log.info("Login succeeded: user={} session={}", account.id(), sessionId);
        return new LoginResult(accessTokens.issue(account.id(), sessionId), refreshSecret,
                accessTokens.ttl().toSeconds());
    }

    private void enforceAccountRateLimit(String email) {
        // The account key is a hash: the email address never sits in the limiter's memory in clear.
        Optional<Long> retryAfter = rateLimiter.tryConsume(Stage.USER, RATE_LIMIT_PROFILE, "login:" + sha256Hex(email));
        if (retryAfter.isPresent()) {
            throw new RateLimitExceededException(retryAfter.get());
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available.", e);
        }
    }
}
