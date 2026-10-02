package com.couplefinance.budget.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;

/** Domain rules of the category limit lines (Issue #25): BR-BUD-01, BR-BUD-02, BR-BUD-04, BR-MON-02/03. */
class CategoryLimitsTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    private static final BudgetPeriod PERIOD = new BudgetPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private static CategoryLimit line(String amount) {
        return new CategoryLimit(UUID.randomUUID(), eur(amount));
    }

    private static void assertCode(Runnable action, BudgetErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void BR_BUD_01_one_limit_per_category() {
        UUID category = UUID.randomUUID();
        List<CategoryLimit> duplicated = List.of(new CategoryLimit(category, eur("10.00")),
                new CategoryLimit(category, eur("20.00")));

        assertCode(() -> CategoryLimits.validate(duplicated, EUR, 2), BudgetErrorCode.DUPLICATE_CATEGORY_LIMIT);
        assertThatCode(() -> CategoryLimits.validate(List.of(line("10.00"), line("10.00")), EUR, 2))
                .doesNotThrowAnyException();
    }

    @Test
    void BR_MON_02_03_a_category_limit_is_strictly_positive_and_bounded() {
        assertThatThrownBy(() -> CategoryLimits.validate(List.of(line("0.00")), EUR, 2))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("AMOUNT_NOT_POSITIVE"));
        assertThatThrownBy(() -> CategoryLimits.validate(List.of(line("-1.00")), EUR, 2))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("AMOUNT_NOT_POSITIVE"));
        assertThatThrownBy(() -> CategoryLimits.validate(List.of(line("10000000000000.01")), EUR, 2))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("AMOUNT_EXCEEDS_MAXIMUM"));
    }

    @Test
    void a_budget_holds_a_bounded_number_of_lines() {
        List<CategoryLimit> tooMany = java.util.stream.IntStream.rangeClosed(0, CategoryLimits.MAX_LINES)
                .mapToObj(i -> line("1.00")).toList();

        assertCode(() -> CategoryLimits.validate(tooMany, EUR, 2), BudgetErrorCode.TOO_MANY_CATEGORY_LIMITS);
    }

    @Test
    void BR_BUD_02_a_budget_may_consist_only_of_category_limits_but_never_of_zero_limits() {
        Budget onlyCategories = Budget.create(new HouseholdId(UUID.randomUUID()), PERIOD, EUR, 2, CLOCK, null, 2,
                new UserId(UUID.randomUUID()));

        assertThat(onlyCategories.overallLimitMinor()).isNull();
        assertCode(() -> Budget.create(new HouseholdId(UUID.randomUUID()), PERIOD, EUR, 2, CLOCK, null, 0,
                new UserId(UUID.randomUUID())), BudgetErrorCode.BUDGET_LIMIT_REQUIRED);
    }

    @Test
    void BR_BUD_02_the_overall_limit_can_be_cleared_only_while_category_limits_remain() {
        UserId user = new UserId(UUID.randomUUID());
        Budget budget = Budget.create(new HouseholdId(UUID.randomUUID()), PERIOD, EUR, 2, CLOCK, eur("100.00"), 1,
                user);

        assertCode(() -> budget.setOverallLimit(null, 0, 2, CLOCK, user), BudgetErrorCode.BUDGET_LIMIT_REQUIRED);
        assertThat(budget.setOverallLimit(null, 1, 2, CLOCK, user)).isTrue();
        assertThat(budget.overallLimitMinor()).isNull();
        assertThat(budget.setOverallLimit(null, 1, 2, CLOCK, user)).isFalse();
    }

    @Test
    void BR_BUD_04_sum_exceeding_overall_warns_but_does_not_block() {
        List<CategoryLimit> lines = List.of(line("60.00"), line("50.00"));

        CategoryLimitsWarning warning = CategoryLimits.warningFor(eur("100.00"), lines, EUR, 2);

        assertThat(warning).isNotNull();
        assertThat(warning.categoryLimitsTotal()).isEqualTo(eur("110.00"));
        assertThat(warning.overallLimit()).isEqualTo(eur("100.00"));
        // the same lines are valid: the warning never blocks
        assertThatCode(() -> CategoryLimits.validate(lines, EUR, 2)).doesNotThrowAnyException();
    }

    @Test
    void BR_BUD_04_no_warning_when_the_sum_does_not_exceed_the_overall_limit_or_there_is_none() {
        assertThat(CategoryLimits.warningFor(eur("110.00"), List.of(line("60.00"), line("50.00")), EUR, 2)).isNull();
        assertThat(CategoryLimits.warningFor(eur("200.00"), List.of(line("60.00")), EUR, 2)).isNull();
        assertThat(CategoryLimits.warningFor(null, List.of(line("60.00")), EUR, 2)).isNull();
        assertThat(CategoryLimits.warningFor(eur("100.00"), List.of(), EUR, 2)).isNull();
    }

    @Test
    void BR_BUD_04_the_sum_is_exact_to_the_minor_unit() {
        assertThat(CategoryLimits.warningFor(eur("0.30"), List.of(line("0.10"), line("0.10"), line("0.11")), EUR, 2))
                .isNotNull().extracting(CategoryLimitsWarning::categoryLimitsTotal).isEqualTo(eur("0.31"));
    }
}
