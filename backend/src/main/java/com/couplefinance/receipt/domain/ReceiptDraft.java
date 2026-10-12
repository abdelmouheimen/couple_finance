package com.couplefinance.receipt.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The review draft of an analysed receipt (database-schema.md section 8.6). Resolved values are present only when
 * unambiguous; every field keeps its raw string, its candidate readings and its status, so the user resolves what
 * the parser could not (BR-RCP-05, BR-RCP-10). A draft is evidence, never money.
 *
 * @param findings consistency findings and warnings (BR-RCP-07/09/12/13), advisory
 */
public record ReceiptDraft(
        DocumentType documentType,
        TaxMode taxMode,
        ProposedKind proposedKind,
        Optional<String> merchantKey,
        Optional<Long> totalMinor,
        Optional<LocalDate> purchaseDate,
        Optional<String> detectedCurrency,
        Map<String, Field> fields,
        List<Line> lines,
        Set<String> findings) {

    public static final String MISSING = "MISSING";

    public ReceiptDraft {
        fields = Map.copyOf(fields);
        lines = List.copyOf(lines);
        findings = Set.copyOf(findings);
    }

    /**
     * @param status a {@link ReceiptFieldStatus} name or {@link #MISSING}
     * @param confidence model-reported, uncalibrated: displayed only (BR-RCP-11)
     */
    public record Field(@Nullable String raw, List<String> candidates, String status,
            @Nullable BigDecimal confidence) {
        public Field {
            candidates = List.copyOf(candidates);
        }
    }

    /** @param amountMinor signed, present only when the printed amount parses to one reading */
    public record Line(int position, LineItemType type, String description, @Nullable String quantityRaw,
            String amountRaw, @Nullable Long amountMinor, String amountStatus) {
    }
}
