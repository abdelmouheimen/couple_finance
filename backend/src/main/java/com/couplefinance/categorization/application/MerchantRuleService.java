package com.couplefinance.categorization.application;

import java.time.Clock;
import java.util.UUID;

import com.couplefinance.categorization.api.CategorySuggestion;
import com.couplefinance.categorization.api.MerchantNames;
import com.couplefinance.categorization.api.NormalisedMerchant;
import com.couplefinance.categorization.api.RuleScope;
import com.couplefinance.categorization.domain.Category;
import com.couplefinance.categorization.domain.CategoryErrorCode;
import com.couplefinance.categorization.domain.CategoryRepository;
import com.couplefinance.categorization.domain.MerchantRuleStore;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.error.CommonErrorCode;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of merchant rules for the authenticated caller (BR-CAT-04, BR-CAT-05): the suggestion read and the
 * explicit "always use this category for this merchant". The household and the user come from the authenticated
 * principal; a PERSONAL rule is always owned by the caller, a SHARED one belongs to the caller's household.
 */
@Service
public class MerchantRuleService {

    /** Same bound as the merchant of an expense (BR-EXP-01). */
    public static final int MERCHANT_MAX_LENGTH = 120;

    private final CurrentHousehold currentHousehold;
    private final MerchantRuleLookup lookup;
    private final MerchantRuleStore rules;
    private final CategoryRepository categories;
    private final Clock clock;

    MerchantRuleService(CurrentHousehold currentHousehold, MerchantRuleLookup lookup, MerchantRuleStore rules,
                        CategoryRepository categories, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.lookup = lookup;
        this.rules = rules;
        this.categories = categories;
        this.clock = clock;
    }

    /** BR-CAT-04: the rule-based suggestion. Readable by archive readers of a dissolved household. */
    @Transactional(readOnly = true)
    public CategorySuggestion suggest(String merchant, RuleScope scope) {
        requireValidLength(merchant);
        HouseholdContext context = currentHousehold.currentHousehold();
        return lookup.suggest(context.householdId(), context.userId(), merchant, scope);
    }

    /**
     * BR-CAT-05: creates or replaces the rule of the caller's scope for the merchant and resets its correction
     * streak. A category that is unknown or of another household is a 404, an archived one a 400.
     */
    @Transactional
    public ExplicitRule setRule(String merchant, UUID categoryId, RuleScope scope) {
        requireValidLength(merchant);
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        NormalisedMerchant normalised = MerchantNames.normalise(merchant);
        if (normalised.isEmpty()) {
            throw new ApplicationException(CommonErrorCode.VALIDATION_FAILED,
                    "The merchant must contain letters or digits.");
        }
        Category category = categories.findVisibleByIdAndHouseholdId(categoryId, context.householdId().value())
                .orElseThrow(() -> new ApplicationException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "The category was not found."));
        if (category.isArchived()) {
            throw new ApplicationException(CategoryErrorCode.CATEGORY_ARCHIVED,
                    "An archived category cannot be used in a merchant rule.");
        }
        UUID household = context.householdId().value();
        UUID owner = scope == RuleScope.PERSONAL ? context.userId().value() : null;
        rules.lockScope(household, owner, normalised.key());
        rules.upsertRule(UuidV7.generate(clock), household, owner, normalised.key(), normalised.normaliserVersion(),
                categoryId, clock.instant());
        rules.deleteStreak(household, owner, normalised.key());
        return new ExplicitRule(normalised.key(), categoryId, scope);
    }

    private static void requireValidLength(String merchant) {
        if (merchant == null || merchant.length() > MERCHANT_MAX_LENGTH) {
            throw new ApplicationException(CommonErrorCode.VALIDATION_FAILED,
                    "The merchant has at most " + MERCHANT_MAX_LENGTH + " characters.");
        }
    }

    /** The rule now in force for the caller's scope. */
    public record ExplicitRule(String merchantKey, UUID categoryId, RuleScope scope) {}
}
