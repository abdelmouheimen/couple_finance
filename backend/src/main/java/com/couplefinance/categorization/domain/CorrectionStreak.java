package com.couplefinance.categorization.domain;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The run of consecutive saves of one merchant that chose the same category against the suggestion (BR-CAT-05).
 * Two such saves create a rule. Pure and immutable.
 *
 * @param count         1 or 2
 * @param lastExpenseId the last expense counted, which makes re-delivery of that save a no-op
 */
public record CorrectionStreak(UUID categoryId, int count, UUID lastExpenseId) {

    /** Number of consecutive corrections that creates a rule (BR-CAT-05). */
    public static final int RULE_THRESHOLD = 2;

    public CorrectionStreak {
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(lastExpenseId, "lastExpenseId");
        if (count < 1 || count > RULE_THRESHOLD) {
            throw new IllegalArgumentException("count must be between 1 and " + RULE_THRESHOLD);
        }
    }

    /**
     * The streak after a save that chose {@code category} against the suggestion.
     *
     * @param current the streak so far, {@code null} when there is none
     */
    public static CorrectionStreak after(@Nullable CorrectionStreak current, UUID category, UUID expenseId) {
        if (current == null || !current.categoryId.equals(category)) {
            return new CorrectionStreak(category, 1, expenseId);
        }
        if (current.lastExpenseId.equals(expenseId)) {
            return current; // re-delivery of the same save
        }
        return new CorrectionStreak(category, RULE_THRESHOLD, expenseId);
    }

    /** Whether the streak is long enough to create or update the rule. */
    public boolean createsRule() {
        return count >= RULE_THRESHOLD;
    }
}
