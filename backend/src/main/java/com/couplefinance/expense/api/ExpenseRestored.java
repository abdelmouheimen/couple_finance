package com.couplefinance.expense.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A deleted expense was restored within its 90-day window (BR-EXP-11). Published in the restoring transaction.
 * Identifiers only; consumers re-read the current state (the expense counts again in totals and budgets).
 *
 * @param ownerUserId {@code null} when the expense is SHARED, else its owner
 */
public record ExpenseRestored(UUID expenseId, UUID householdId, @Nullable UUID ownerUserId, Instant occurredAt) {

    public ExpenseRestored {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
