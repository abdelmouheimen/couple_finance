package com.couplefinance.receipt.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.jspecify.annotations.Nullable;

/**
 * Outbound persistence port of receipts. Every read is scoped by household and by visibility: a receipt is visible
 * to a member when it is their own draft (BR-RCP-16). Unknown, foreign-household and partner-draft receipts are
 * indistinguishable (empty). There is deliberately no unscoped finder.
 */
public interface ReceiptRepository {

    void insert(Receipt receipt);

    Optional<Receipt> findVisible(UUID id, HouseholdId household, UserId viewer);

    /** Same as {@link #findVisible} and locks the receipt row until the end of the transaction. */
    Optional<Receipt> lockVisible(UUID id, HouseholdId household, UserId viewer);

    /**
     * Receipts visible to {@code viewer}, newest first (created_at, then id), after the given position.
     *
     * @param afterCreatedAt position of the last item of the previous page, {@code null} for the first page
     */
    List<Receipt> listVisible(HouseholdId household, UserId viewer, @Nullable Instant afterCreatedAt,
            @Nullable UUID afterId, int limit);

    /** Deletes a receipt of the household and its pages (rows only; stored objects are the caller's concern). */
    void delete(UUID id, HouseholdId household);
}
