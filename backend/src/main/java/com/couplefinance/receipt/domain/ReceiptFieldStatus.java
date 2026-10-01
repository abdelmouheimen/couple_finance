package com.couplefinance.receipt.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Per-field validation status shown by the review step (ai.md §4.4/§4.5). Validation never changes a value; it
 * only labels it.
 */
public enum ReceiptFieldStatus {
    OK,
    AMBIGUOUS,
    INVALID,
    INCONSISTENT,
    NOT_CORROBORATED,
    STALE,
    LOW_CONFIDENCE;

    /** Status implied by a parsing outcome (BR-RCP-04/05/08); a {@code STALE} warning yields {@link #STALE}. */
    public static ReceiptFieldStatus fromParse(ParseResult<?> result) {
        return switch (result) {
            case ParseResult.Resolved<?> resolved -> resolved.warnings().contains(ParseWarning.STALE) ? STALE : OK;
            case ParseResult.Ambiguous<?> ignored -> AMBIGUOUS;
            case ParseResult.Invalid<?> ignored -> INVALID;
        };
    }

    /**
     * BR-RCP-11: confidence is only used once a calibrated threshold exists for the field, and a validation status
     * other than {@link #OK} always takes precedence. A confidence outside [0, 1] is malformed model output and is
     * treated as low (never clamped).
     */
    public static ReceiptFieldStatus withConfidence(ReceiptFieldStatus validation, Optional<BigDecimal> confidence,
            Optional<BigDecimal> calibratedThreshold) {
        if (validation != OK || confidence.isEmpty() || calibratedThreshold.isEmpty()) {
            return validation;
        }
        BigDecimal value = confidence.get();
        boolean inRange = value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
        return !inRange || value.compareTo(calibratedThreshold.get()) < 0 ? LOW_CONFIDENCE : OK;
    }
}
