package com.couplefinance.receipt.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.jspecify.annotations.Nullable;

/**
 * Outbound persistence port of the asynchronous analysis (database-schema.md sections 8.4–8.7). Every state change
 * is conditional on the receipt still being open with the expected analysis status, so a result arriving after the
 * user confirmed, deleted or withdrew consent is discarded, never applied (section 8.1). Methods that write run in
 * the caller's transaction.
 */
public interface ReceiptAnalysisStore {

    enum Trigger { UPLOAD, MANUAL_RETRY, CONSENT_GRANTED }

    /** A job claimed by a worker, with what is needed to analyse it (scoped by its stored household). */
    record Claim(UUID jobId, UUID receiptId, HouseholdId householdId, UserId uploadedBy, int attemptCount,
            List<PageRef> pages) {
        public Claim {
            pages = List.copyOf(pages);
        }
    }

    record PageRef(String storageKey, String contentType) {
    }

    /** One provider attempt, stored as untrusted evidence (BR-RCP-18). */
    record Attempt(String provider, String model, String promptVersion, String outcome, @Nullable String rawOutput,
            long latencyMs) {
    }

    void enqueue(UUID receiptId, HouseholdId household, Trigger trigger, Instant now);

    /**
     * Re-queues an expired lease, then claims up to {@code limit} ready jobs (FOR UPDATE SKIP LOCKED) and marks their
     * receipts RUNNING. Jobs whose receipt is no longer open and pending are cancelled instead of claimed.
     */
    List<Claim> claim(String worker, Instant now, Instant leaseUntil, int limit);

    /** Applies a successful analysis; {@code false} when the receipt changed meanwhile (result discarded). */
    boolean succeed(Claim claim, Attempt attempt, ReceiptDraft draft, Instant now);

    /**
     * Records a failure. With {@code retryAt} the job is re-queued (receipt PENDING); without, the job and the receipt
     * end FAILED with {@code failureReason}.
     *
     * @param attempt   {@code null} when no provider call was made (e.g. storage read failure)
     * @param processed whether content reached the provider (consent audit, ai_processed)
     */
    void fail(Claim claim, @Nullable Attempt attempt, String errorCode, @Nullable Instant retryAt,
            String failureReason, boolean processed, Instant now);

    /** Cancels a claimed job without a provider call (consent withdrawn); the receipt becomes CANCELLED. */
    void cancel(Claim claim, Instant now);

    /**
     * Queues an explicit analysis of a locked, open receipt (BR-RCP-17).
     *
     * @param manualRetry counts against {@link Receipt#MAX_MANUAL_RETRIES}
     */
    void request(UUID receiptId, HouseholdId household, Trigger trigger, boolean manualRetry, Instant now);

    /**
     * BR-AI-05: withdrawal is effective immediately for queued jobs. Cancels the pending jobs of the receipts this user
     * uploaded (their own work only; running jobs re-check consent before the provider call).
     *
     * @return number of jobs cancelled
     */
    int cancelPendingOf(UserId uploader, Instant now);
}
