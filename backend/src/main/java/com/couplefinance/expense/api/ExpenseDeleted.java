package com.couplefinance.expense.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An expense was logically deleted (BR-EXP-11, domain-model.md section 7.5). Published in the deleting
 * transaction through the transactional event publication registry. It carries identifiers only (no amount,
 * merchant or note): consumers re-read the current state.
 *
 * <p>BR-NOT-02 / BR-NOT-03: {@code createdBy} is filled ONLY for a SHARED expense, so that the notification
 * consumer can inform the partner who created it (when {@code createdBy} differs from {@code deletedBy}). For a
 * PERSONAL expense it is {@code null}: nothing about it may reach the partner.
 *
 * @param ownerUserId {@code null} when the expense is SHARED, else its owner
 * @param createdBy   the creator of the expense when it is SHARED, else {@code null}
 */
public record ExpenseDeleted(UUID expenseId, UUID householdId, @Nullable UUID ownerUserId, UUID deletedBy,
        @Nullable UUID createdBy, Instant occurredAt) {

    public ExpenseDeleted {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(deletedBy, "deletedBy");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
