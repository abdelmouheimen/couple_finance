package com.couplefinance.categorization.api;

import java.util.Objects;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.jspecify.annotations.Nullable;

/**
 * One saved expense as seen by rule learning: its merchant (already normalised) and the single category the user
 * chose for it.
 *
 * @param owner {@code null} for a SHARED expense (household-level learning), the owner for a PERSONAL one
 *              (user-level learning, never household-level; BR-CAT-05)
 */
public record LearnedSave(HouseholdId household, @Nullable UserId owner, NormalisedMerchant merchant,
                          UUID categoryId, UUID expenseId) {

    public LearnedSave {
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(expenseId, "expenseId");
    }
}
