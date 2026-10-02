package com.couplefinance.categorization.application;

import java.util.Optional;
import java.util.UUID;

import com.couplefinance.categorization.api.CategorySuggestion;
import com.couplefinance.categorization.api.CategorySuggestions;
import com.couplefinance.categorization.api.MerchantNames;
import com.couplefinance.categorization.api.NormalisedMerchant;
import com.couplefinance.categorization.api.RuleScope;
import com.couplefinance.categorization.domain.MerchantRuleStore;
import com.couplefinance.categorization.domain.SuggestionPolicy;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-CAT-04 rule-based suggestion. Keyed lookups only (at most two): household rule and, for PERSONAL, the
 * caller's own user rule. The partner's user rules are unreachable by construction: the owner is always the
 * given user.
 */
@Service
class MerchantRuleLookup implements CategorySuggestions {

    private final MerchantRuleStore rules;

    MerchantRuleLookup(MerchantRuleStore rules) {
        this.rules = rules;
    }

    @Override
    @Transactional(readOnly = true)
    public CategorySuggestion suggest(HouseholdId household, UserId user, String rawMerchant, RuleScope scope) {
        NormalisedMerchant merchant = MerchantNames.normalise(rawMerchant);
        if (merchant.isEmpty()) {
            return SuggestionPolicy.resolve(scope, Optional.empty(), Optional.empty());
        }
        return suggestForKey(household.value(), user.value(), merchant.key(), scope);
    }

    /** Same lookup for an already normalised key; {@code user} is ignored for SHARED. */
    CategorySuggestion suggestForKey(UUID household, UUID user, String key, RuleScope scope) {
        Optional<UUID> userRule = scope == RuleScope.PERSONAL
                ? rules.findActiveRuleCategory(household, user, key)
                : Optional.empty();
        Optional<UUID> householdRule = rules.findActiveRuleCategory(household, null, key);
        return SuggestionPolicy.resolve(scope, userRule, householdRule);
    }
}
