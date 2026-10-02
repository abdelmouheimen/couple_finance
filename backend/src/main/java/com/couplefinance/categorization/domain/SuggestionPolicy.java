package com.couplefinance.categorization.domain;

import java.util.Optional;
import java.util.UUID;

import com.couplefinance.categorization.api.CategorySuggestion;
import com.couplefinance.categorization.api.RuleScope;
import com.couplefinance.categorization.api.SuggestionSource;

/**
 * BR-CAT-04 precedence of the rule-based steps: the user's private rule (PERSONAL only), then the household
 * rule, then the system category "Other". Pure. The AI step (2) is not part of this Issue.
 */
public final class SuggestionPolicy {

    /** Fixed id of the system category OTHER (migration categorization-0003). */
    public static final UUID OTHER_CATEGORY_ID = UUID.fromString("019a0000-0000-7000-8000-000000000012");

    private SuggestionPolicy() {}

    /**
     * @param userRule      active rule of the caller's user scope; ignored for SHARED
     * @param householdRule active household rule
     */
    public static CategorySuggestion resolve(RuleScope scope, Optional<UUID> userRule, Optional<UUID> householdRule) {
        if (scope == RuleScope.PERSONAL && userRule.isPresent()) {
            return new CategorySuggestion(userRule.get(), SuggestionSource.USER_RULE);
        }
        return householdRule.map(id -> new CategorySuggestion(id, SuggestionSource.HOUSEHOLD_RULE))
                .orElseGet(() -> new CategorySuggestion(OTHER_CATEGORY_ID, SuggestionSource.DEFAULT_OTHER));
    }
}
