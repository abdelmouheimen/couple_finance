package com.couplefinance.categorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.couplefinance.categorization.api.CategorySuggestion;
import com.couplefinance.categorization.api.LearnedSave;
import com.couplefinance.categorization.api.MerchantRuleLearning;
import com.couplefinance.categorization.api.RuleScope;
import com.couplefinance.categorization.domain.CategoryRepository;
import com.couplefinance.categorization.domain.CorrectionStreak;
import com.couplefinance.categorization.domain.MerchantRuleStore;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-CAT-05 implicit learning. A SHARED save (no owner) feeds the household rule; a PERSONAL save feeds the
 * owner's private user rule only, so the household never learns from PERSONAL expenses and the partner is never
 * influenced. The current suggestion is re-read here (never taken from an event); the whole step runs under a
 * per-scope lock, so concurrent or re-delivered saves cannot double count.
 */
@Service
class MerchantRuleLearner implements MerchantRuleLearning {

    private final MerchantRuleStore rules;
    private final CategoryRepository categories;
    private final MerchantRuleLookup suggestions;
    private final Clock clock;

    MerchantRuleLearner(MerchantRuleStore rules, CategoryRepository categories, MerchantRuleLookup suggestions,
                        Clock clock) {
        this.rules = rules;
        this.categories = categories;
        this.suggestions = suggestions;
        this.clock = clock;
    }

    /** Always its own transaction: it is called after the commit of the saved expense (REQUIRES_NEW). */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void learn(LearnedSave save) {
        if (save.merchant().isEmpty()) {
            return;
        }
        UUID household = save.household().value();
        UUID owner = save.owner() == null ? null : save.owner().value();
        String key = save.merchant().key();
        boolean usable = categories.findVisibleByIdAndHouseholdId(save.categoryId(), household)
                .filter(category -> !category.isArchived()).isPresent();
        if (!usable) {
            return;
        }
        rules.lockScope(household, owner, key);

        RuleScope scope = owner == null ? RuleScope.SHARED : RuleScope.PERSONAL;
        CategorySuggestion suggested = suggestions.suggestForKey(household, owner, key, scope);
        if (suggested.categoryId().equals(save.categoryId())) {
            rules.deleteStreak(household, owner, key); // the suggestion was followed: the streak is broken
            return;
        }
        CorrectionStreak current = rules.findStreak(household, owner, key).orElse(null);
        CorrectionStreak next = CorrectionStreak.after(current, save.categoryId(), save.expenseId());
        Instant now = clock.instant();
        if (next.createsRule()) {
            rules.upsertRule(UuidV7.generate(clock), household, owner, key, save.merchant().normaliserVersion(),
                    save.categoryId(), now);
            rules.deleteStreak(household, owner, key);
        } else if (!next.equals(current)) {
            rules.saveStreak(UuidV7.generate(clock), household, owner, key, next, now);
        }
    }
}
