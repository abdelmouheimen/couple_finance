package com.couplefinance.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.identity.domain.AccountRegistrationStore;
import com.couplefinance.identity.domain.PasswordPolicy;
import com.couplefinance.identity.domain.UserAccount;
import com.couplefinance.identity.domain.UserAccountRepository;
import com.couplefinance.identity.domain.UserAccountStatus;
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
 * Registration and email verification (security.md section 3, BR-HH-16).
 *
 * <p>Registration and resend never reveal whether an email is registered: the caller always gets the same
 * outcome, and exactly one email is sent in the registration cases (a verification mail for a new account, a notice
 * mail to the owner of an existing one). The password is hashed (Argon2id) in every registration case, so the
 * timing does not depend on the email being known. Emails are sent after the database transaction has committed
 * (CLAUDE.md 6.3); a delivery failure is logged without any address and never surfaces to the caller.
 */
@Service
public class RegistrationService {

    /** Default account locale when the client supplies none (approved decision). */
    public static final String DEFAULT_LOCALE = "fr-FR";

    /** Lifetime of an email verification token (approved decision: 24 hours). */
    public static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserAccountRepository accounts;
    private final AccountRegistrationStore store;
    private final PasswordEncoder passwordEncoder;
    private final EmailDelivery email;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final TransactionTemplate transaction;

    RegistrationService(UserAccountRepository accounts, AccountRegistrationStore store,
                        PasswordEncoder passwordEncoder, EmailDelivery email, RateLimiter rateLimiter, Clock clock,
                        PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void register(RegisterCommand command) {
        String address = normalize(command.email());
        enforceAddressRateLimit("register", address);
        PasswordPolicy.requireAcceptable(command.password());
        String locale = command.locale() == null ? DEFAULT_LOCALE : command.locale();

        String hash = passwordEncoder.encode(command.password());
        Instant now = clock.instant();
        UUID userId = UuidV7.generate(clock);
        String secret = RefreshTokenSecrets.generate();
        boolean created = Boolean.TRUE.equals(transaction.execute(status -> {
            if (!store.insertPendingAccount(userId, address, hash, command.displayName().strip(), locale, now)) {
                return false;
            }
            store.insertEmailVerificationToken(UuidV7.generate(clock), userId, RefreshTokenSecrets.hash(secret), now,
                    now.plus(VERIFICATION_TOKEN_TTL));
            return true;
        }));
        if (created) {
            deliver(verificationMail(address, secret));
            log.info("Account registered: user={}", userId);
        } else {
            deliver(existingAccountMail(address));
        }
    }

    /** Sends a fresh verification token to a pending account; same silent outcome for any other address. */
    public void resendVerification(String rawEmail) {
        String address = normalize(rawEmail);
        enforceAddressRateLimit("resend", address);
        Optional<UserAccount> candidate = accounts.findLoginCandidateByLowerEmail(address);
        if (candidate.isEmpty()) {
            return;
        }
        UserAccount account = candidate.get();
        if (account.status() != UserAccountStatus.PENDING_VERIFICATION) {
            deliver(existingAccountMail(address));
            return;
        }
        Instant now = clock.instant();
        String secret = RefreshTokenSecrets.generate();
        transaction.executeWithoutResult(status -> store.insertEmailVerificationToken(UuidV7.generate(clock),
                account.id(), RefreshTokenSecrets.hash(secret), now, now.plus(VERIFICATION_TOKEN_TTL)));
        deliver(verificationMail(address, secret));
    }

    /**
     * Consumes the token and activates the account atomically.
     *
     * @throws ApplicationException {@code INVALID_OR_EXPIRED_TOKEN} for an unknown, expired or used token
     */
    public void verifyEmail(String token) {
        boolean verified = Boolean.TRUE.equals(transaction.execute(status ->
                store.consumeEmailVerificationToken(RefreshTokenSecrets.hash(token), clock.instant())));
        if (!verified) {
            throw new ApplicationException(IdentityErrorCode.INVALID_OR_EXPIRED_TOKEN,
                    "The verification token is invalid or has expired.");
        }
    }

    private void deliver(EmailMessage message) {
        try {
            email.send(message);
        } catch (RuntimeException e) {
            // No address, token or exception message: delivery failures must stay indistinguishable and unleaked.
            log.warn("Email delivery failed: {}", e.getClass().getSimpleName());
        }
    }

    private static EmailMessage verificationMail(String to, String secret) {
        return new EmailMessage(to, "Verify your CoupleFinance email",
                "Your verification code is: " + secret + "\nIt is valid for 24 hours and can be used once.");
    }

    private static EmailMessage existingAccountMail(String to) {
        return new EmailMessage(to, "CoupleFinance account notice",
                "Someone tried to register or verify this address, which already has a CoupleFinance account. "
                        + "If it was you, log in instead. If not, you can ignore this message.");
    }

    private static String normalize(String rawEmail) {
        return rawEmail.strip().toLowerCase(Locale.ROOT);
    }

    /** Per-address limit against mail bombing; keyed on a hash, identical whether or not the address is known. */
    private void enforceAddressRateLimit(String operation, String address) {
        String key = operation + ":" + HexFormat.of().formatHex(RefreshTokenSecrets.hash(address));
        Optional<Long> retryAfter = rateLimiter.tryConsume(Stage.USER, LoginService.RATE_LIMIT_PROFILE, key);
        if (retryAfter.isPresent()) {
            throw new RateLimitExceededException(retryAfter.get());
        }
    }
}
