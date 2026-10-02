package com.couplefinance.categorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import com.couplefinance.categorization.api.CategorySuggestion;
import com.couplefinance.categorization.api.RuleScope;
import com.couplefinance.categorization.api.SuggestionSource;
import org.junit.jupiter.api.Test;

/** BR-CAT-04 precedence and BR-CAT-05 streak, as pure domain rules. */
class MerchantRuleDomainTest {

    private static final UUID USER_CATEGORY = UUID.randomUUID();
    private static final UUID HOUSEHOLD_CATEGORY = UUID.randomUUID();
    private static final UUID X = UUID.randomUUID();
    private static final UUID Y = UUID.randomUUID();

    @Test
    void BR_CAT_04_rule_precedence_personal_user_rule_then_household_rule_then_other() {
        assertThat(SuggestionPolicy.resolve(RuleScope.PERSONAL, Optional.of(USER_CATEGORY),
                Optional.of(HOUSEHOLD_CATEGORY))).isEqualTo(new CategorySuggestion(USER_CATEGORY,
                SuggestionSource.USER_RULE));
        assertThat(SuggestionPolicy.resolve(RuleScope.PERSONAL, Optional.empty(), Optional.of(HOUSEHOLD_CATEGORY)))
                .isEqualTo(new CategorySuggestion(HOUSEHOLD_CATEGORY, SuggestionSource.HOUSEHOLD_RULE));
        assertThat(SuggestionPolicy.resolve(RuleScope.PERSONAL, Optional.empty(), Optional.empty()))
                .isEqualTo(new CategorySuggestion(SuggestionPolicy.OTHER_CATEGORY_ID,
                        SuggestionSource.DEFAULT_OTHER));
    }

    @Test
    void BR_CAT_04_shared_expenses_only_use_household_rules() {
        assertThat(SuggestionPolicy.resolve(RuleScope.SHARED, Optional.of(USER_CATEGORY),
                Optional.of(HOUSEHOLD_CATEGORY)).source()).isEqualTo(SuggestionSource.HOUSEHOLD_RULE);
        assertThat(SuggestionPolicy.resolve(RuleScope.SHARED, Optional.of(USER_CATEGORY), Optional.empty()).source())
                .isEqualTo(SuggestionSource.DEFAULT_OTHER);
    }

    @Test
    void BR_CAT_05_two_consecutive_corrections_create_rule() {
        CorrectionStreak first = CorrectionStreak.after(null, X, UUID.randomUUID());
        assertThat(first.count()).isEqualTo(1);
        assertThat(first.createsRule()).isFalse();

        CorrectionStreak second = CorrectionStreak.after(first, X, UUID.randomUUID());
        assertThat(second.count()).isEqualTo(2);
        assertThat(second.createsRule()).isTrue();
    }

    @Test
    void BR_CAT_05_a_different_category_restarts_the_streak() {
        CorrectionStreak first = CorrectionStreak.after(null, X, UUID.randomUUID());

        CorrectionStreak other = CorrectionStreak.after(first, Y, UUID.randomUUID());

        assertThat(other.count()).isEqualTo(1);
        assertThat(other.categoryId()).isEqualTo(Y);
    }

    @Test
    void BR_CAT_05_redelivery_of_the_same_expense_is_not_counted_twice() {
        UUID expense = UUID.randomUUID();
        CorrectionStreak first = CorrectionStreak.after(null, X, expense);

        assertThat(CorrectionStreak.after(first, X, expense)).isEqualTo(first);
    }

    @Test
    void a_streak_count_outside_one_to_two_is_rejected() {
        assertThatThrownBy(() -> new CorrectionStreak(X, 3, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
