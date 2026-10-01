package com.couplefinance.shared.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.couplefinance.shared.error.ApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exactly-once execution of creating requests per (user, {@code Idempotency-Key}) (BR-EXP-13).
 *
 * <ol>
 *   <li>The key is claimed by an insert in its own committed transaction; the primary key arbitrates races.
 *   <li>The winner runs the action (the use case, with its own transaction); no transaction of this service is
 *       open meanwhile, so no pooled connection is held by it across the action, nor across any outbound call
 *       the action makes.
 *   <li>The response is stored in a second short transaction; later requests with the same key and the same
 *       request get it back unchanged, for 24 h.
 * </ol>
 *
 * Same key and a different request → 422 {@code IDEMPOTENCY_KEY_REUSED}; same key while the first is running →
 * 409 {@code REQUEST_IN_PROGRESS}. If the action throws, the claim is released so the client may retry. If the
 * process dies between the action's commit and the storing of the response, the key stays {@code IN_PROGRESS}
 * (409) until it expires after 24 h: duplicates are impossible, at the price of a blocked retry.
 *
 * <p>The scope is the authenticated user id supplied by the caller (never a client value), so one user's keys
 * and responses are invisible to every other user. Callers must invoke this outside any transaction (enforced: an
 * {@link IllegalStateException} is thrown otherwise), so the action is the unit of work that commits. The
 * response body must be valid JSON (stored as {@code jsonb}, so a replay is semantically equal but not
 * necessarily byte-identical: key order and whitespace may differ).
 */
@Service
public class IdempotencyService {

    public static final Duration VALIDITY = Duration.ofHours(24);

    private static final int MAX_CLAIM_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final JdbcIdempotencyStore store;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    IdempotencyService(JdbcIdempotencyStore store, PlatformTransactionManager transactionManager, Clock clock) {
        this.store = store;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * @param userId the authenticated user (scope of the key)
     * @param operation stable operation name, e.g. {@code expense.create}
     * @param requestHash {@link RequestHash} of the canonical request
     * @param action executes the request and returns the response to store; runs at most once per key
     * @throws ApplicationException {@code IDEMPOTENCY_KEY_REUSED} (422) or {@code REQUEST_IN_PROGRESS} (409)
     */
    public IdempotentResult execute(UUID userId, IdempotencyKey key, String operation, byte[] requestHash,
            Supplier<IdempotentResponse> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The claim and the response commit independently; inside a caller transaction they could outlive a
            // rollback of the action's own work and replay a response for data that does not exist.
            throw new IllegalStateException("IdempotencyService.execute must be called outside a transaction");
        }
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            boolean claimed = Boolean.TRUE.equals(requiresNew.execute(status ->
                    store.claim(userId, key.value(), operation, requestHash, now, now.plus(VALIDITY))));
            if (claimed) {
                return new IdempotentResult(runClaimed(userId, key, requestHash, now, action), false);
            }
            Optional<IdempotencyEntry> existing = store.find(userId, key.value());
            if (existing.isEmpty()) {
                continue; // released or purged between our claim attempt and our read: claim again
            }
            IdempotencyEntry entry = existing.get();
            if (!entry.operation().equals(operation) || !RequestHash.matches(entry.requestHash(), requestHash)) {
                throw new ApplicationException(IdempotencyErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different request.");
            }
            if (entry.done()) {
                return new IdempotentResult(new IdempotentResponse(entry.responseStatus(), entry.responseBody()), true);
            }
            throw inProgress();
        }
        throw inProgress();
    }

    private IdempotentResponse runClaimed(UUID userId, IdempotencyKey key, byte[] requestHash, Instant claimedAt,
            Supplier<IdempotentResponse> action) {
        IdempotentResponse response;
        try {
            response = action.get();
        } catch (RuntimeException | Error e) {
            releaseQuietly(userId, key, requestHash, claimedAt);
            throw e;
        }
        try {
            requiresNew.executeWithoutResult(status -> store.complete(userId, key.value(), requestHash, claimedAt, response));
        } catch (RuntimeException e) {
            // The request succeeded: report it. The key stays IN_PROGRESS until it expires (no duplicate possible).
            log.error("Could not store the idempotent response; key stays IN_PROGRESS until expiry", e);
        }
        return response;
    }

    private void releaseQuietly(UUID userId, IdempotencyKey key, byte[] requestHash, Instant claimedAt) {
        try {
            requiresNew.executeWithoutResult(status -> store.release(userId, key.value(), requestHash, claimedAt));
        } catch (RuntimeException e) {
            log.error("Could not release the idempotency key of a failed request; it expires in 24 h", e);
        }
    }

    private static ApplicationException inProgress() {
        return new ApplicationException(IdempotencyErrorCode.REQUEST_IN_PROGRESS,
                "A request with this Idempotency-Key is still being processed.");
    }

    /** Removes expired keys; returns how many. Idempotent and safe to run on several instances. */
    public int purgeExpired() {
        return store.purgeExpired(clock.instant());
    }
}
