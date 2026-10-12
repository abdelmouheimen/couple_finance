package com.couplefinance.receipt.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.jspecify.annotations.Nullable;

/**
 * Outbound persistence port of the review and confirmation of a receipt (database-schema.md sections 8.6–8.9).
 * Every query is scoped by household; the caller filters related receipts by what the viewer may see.
 */
public interface ReceiptReviewStore {

    /** Another receipt of the household that looks like the same document (BR-RCP-14 a, b). */
    record RelatedReceipt(UUID id, ReceiptStatus status, UserId uploadedBy) {
    }

    /**
     * The immutable record of what a member confirmed (decision S10, BR-RCP-18).
     *
     * @param mode             {@code NEW_EXPENSE} or {@code ATTACH}
     * @param acknowledgedTotalMinor required for {@code NEW_EXPENSE}
     */
    record Confirmation(UUID receiptId, HouseholdId household, String mode, UserId confirmedBy, Instant confirmedAt,
            @Nullable Long acknowledgedTotalMinor, @Nullable LocalDate acknowledgedDate,
            Map<String, String> resolutions, String idempotencyKey) {
        public Confirmation {
            resolutions = Map.copyOf(resolutions);
        }
    }

    /** The review draft of an analysed receipt. */
    Optional<ReceiptDraft> draft(UUID receiptId, HouseholdId household);

    /** Other receipts of the household holding a page with the same original bytes as one of this receipt's. */
    List<RelatedReceipt> sameFile(Receipt receipt);

    /** Other receipts of the household holding a page whose perceptual hash is within {@code maxDistance} bits. */
    List<RelatedReceipt> similarImage(Receipt receipt, int maxDistance);

    /**
     * Links an open receipt, exactly once (BR-RCP-15): conditional update OPEN → {@code status} on the reviewed
     * version; a queued or running analysis is cancelled with its jobs (its result will be discarded).
     *
     * @return {@code false} when the receipt is no longer open in that version: nothing changed
     */
    boolean link(UUID receiptId, HouseholdId household, ReceiptStatus status, long reviewedVersion, Instant now);

    /** Inserts the snapshot; its primary key forbids a second confirmation of the same receipt. */
    void record(Confirmation confirmation);
}
