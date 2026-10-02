package com.couplefinance.expense.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An expense was permanently purged after its retention period (BR-EXP-11, domain-model.md section 7.4), with its
 * items and audit rows. Published by the purge job in the purging transaction through the transactional event
 * publication registry. It carries identifiers only (no amount, merchant or note). {@code receiptId} lets the
 * receipt module purge the linked receipt idempotently; the expense itself no longer exists, so consumers cannot
 * re-read it.
 *
 * @param receiptId the receipt that was linked to the expense, or {@code null}
 */
public record ExpensePurged(UUID expenseId, UUID householdId, @Nullable UUID ownerUserId,
        @Nullable UUID receiptId, Instant occurredAt) {

    public ExpensePurged {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
