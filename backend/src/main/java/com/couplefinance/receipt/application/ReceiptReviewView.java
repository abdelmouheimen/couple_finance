package com.couplefinance.receipt.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.receipt.domain.ReceiptState;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * What the member reviews before confirming (BR-RCP-10): proposals only. Resolved values are present only when the
 * deterministic parsing found exactly one reading; every field keeps its printed string, its candidates and its
 * status. Nothing here is an expense.
 *
 * @param version           the receipt version to send back with the confirmation
 * @param analysed          whether a draft exists; otherwise the member enters every value (manual entry)
 * @param proposedTotal     in the household currency, present only when unambiguous
 * @param unresolvedFields  fields whose status is not OK: each must be resolved individually
 * @param suggestedCategory rule-based, from the merchant name only, always a usable category of the household
 */
public record ReceiptReviewView(UUID receiptId, long version, ReceiptState state, boolean analysed,
        @Nullable String documentType, boolean refundProposed, String householdCurrency,
        @Nullable String proposedMerchant, @Nullable LocalDate proposedDate, @Nullable Money proposedTotal,
        @Nullable String detectedCurrency, Map<String, FieldView> fields, List<String> unresolvedFields,
        List<LineView> lines, List<String> findings, @Nullable SuggestedCategory suggestedCategory,
        List<Duplicate> duplicates) {

    public ReceiptReviewView {
        fields = Map.copyOf(fields);
        unresolvedFields = List.copyOf(unresolvedFields);
        lines = List.copyOf(lines);
        findings = List.copyOf(findings);
        duplicates = List.copyOf(duplicates);
    }

    /** @param confidence model-reported, uncalibrated: display only (BR-RCP-11) */
    public record FieldView(@Nullable String raw, List<String> candidates, String status,
            @Nullable BigDecimal confidence) {
    }

    /** A printed line of the receipt: evidence, not an expense item. */
    public record LineView(int position, String type, String description, String amountRaw,
            @Nullable Money amount, String amountStatus) {
    }

    /** @param source {@code USER_RULE}, {@code HOUSEHOLD_RULE} or {@code DEFAULT_OTHER} */
    public record SuggestedCategory(UUID categoryId, String source) {
    }

    public enum DuplicateReason { SAME_FILE, SIMILAR_IMAGE, MATCHING_EXPENSE }

    /**
     * A potential duplicate the viewer may see (BR-RCP-14): a warning, never a block.
     *
     * @param receiptId  the other receipt, for SAME_FILE / SIMILAR_IMAGE
     * @param expenseId  the existing expense, when there is one the viewer may see
     * @param attachable whether this receipt can be attached to that expense instead of creating a new one
     */
    public record Duplicate(DuplicateReason reason, @Nullable UUID receiptId, @Nullable UUID expenseId,
            boolean attachable) {
    }
}
