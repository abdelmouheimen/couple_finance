package com.couplefinance.categorization.api;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;

/** Rule-based category suggestion (BR-CAT-04 steps 1 and 3). Read-only and always household-scoped. */
public interface CategorySuggestions {

    /**
     * Suggests a category for a raw merchant string. For {@link RuleScope#SHARED} only household rules apply; for
     * {@link RuleScope#PERSONAL} the rule of {@code user} applies first, then the household rule. A rule pointing
     * to an archived category is skipped. Falls back to the system category "Other" (also for a blank or
     * information-free merchant). The private rules of the partner are never consulted.
     *
     * @param household the caller's household, from its {@code HouseholdContext}
     * @param user      the authenticated user (owner of the PERSONAL expense)
     */
    CategorySuggestion suggest(HouseholdId household, UserId user, String rawMerchant, RuleScope scope);
}
