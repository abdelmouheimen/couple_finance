package com.couplefinance.expense.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Unscoped persistence of the retention purge (security.md section 4.4): the only expense persistence allowed to
 * work across households, because the purge job is not acting for a user. Only purge classes may depend on it
 * (enforced by an architecture test). Every method works on a bounded batch and locks rows with
 * {@code FOR UPDATE SKIP LOCKED}, so concurrent instances never process the same row; both must be called in the
 * caller's transaction.
 */
public interface ExpensePurgeRepository {

    /** An expense removed by {@link #purgeDeletedBefore}. */
    record PurgedExpense(UUID expenseId, UUID householdId, @Nullable UUID ownerUserId, @Nullable UUID receiptId) {}

    /**
     * Permanently deletes up to {@code limit} expenses whose {@code deleted_at} is strictly before {@code cutoff},
     * with their items and audit rows (BR-EXP-11). An expense still referenced by another expense (a refund) is
     * skipped until the referencing rows are purged.
     */
    List<PurgedExpense> purgeDeletedBefore(Instant cutoff, int limit);

    /** Deletes up to {@code limit} audit rows occurred strictly before {@code cutoff} (BR-DAT: 24 months). */
    int purgeAuditBefore(Instant cutoff, int limit);
}
