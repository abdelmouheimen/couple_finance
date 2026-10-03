package com.couplefinance.budget.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.budget.domain.BudgetCategoryLimits.Line;
import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;

/** Issue #26: BR-BUD-02 (copy, no recurrence, no rollover), BR-CAT-03 (archived categories not copied). */
class BudgetCopyTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    private static final BudgetPeriod OCTOBER = new BudgetPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));
    private static final BudgetPeriod NOVEMBER = new BudgetPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1));

    private final HouseholdId household = new HouseholdId(UUID.randomUUID());
    private final UserId user = new UserId(UUID.randomUUID());

    @Test
    void BR_CAT_03_archived_categories_not_copied() {
        UUID active = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        List<Line> source = List.of(new Line(active, 100), new Line(archived, 200));

        assertThat(BudgetCopy.copiedLines(source, Set.of(archived))).containsExactly(new Line(active, 100));
        assertThat(BudgetCopy.copiedLines(source, Set.of())).isEqualTo(source);
    }

    @Test
    void BR_BUD_02_no_automatic_recurrence_a_budget_only_exists_for_its_own_period() {
        Budget october = Budget.create(household, OCTOBER, EUR, 2, CLOCK, Money.parse("500.00", EUR, 2), user);

        // a budget is keyed by its period; nothing in the aggregate produces the next one by itself
        assertThat(october.periodStart()).isEqualTo(OCTOBER.start());
        assertThat(october.copiedFromBudgetId()).isNull();
    }

    @Test
    void BR_BUD_02_a_copy_keeps_the_overall_limit_records_its_source_and_carries_nothing_else_over() {
        Budget october = Budget.create(household, OCTOBER, EUR, 2, CLOCK, Money.parse("500.00", EUR, 2), user);

        Budget november = Budget.copyOf(october, household, NOVEMBER, 2, CLOCK, 0, user);

        assertThat(november.id()).isNotEqualTo(october.id());
        assertThat(november.copiedFromBudgetId()).isEqualTo(october.id());
        assertThat(november.overallLimitMinor()).isEqualTo(50_000L); // limit as is: no rollover of unused amount
        assertThat(november.periodStart()).isEqualTo(NOVEMBER.start());
        assertThat(november.periodEnd()).isEqualTo(NOVEMBER.end());
    }

    @Test
    void BR_BUD_02_a_copy_without_any_limit_is_rejected() {
        Budget categoryOnly = Budget.create(household, OCTOBER, EUR, 2, CLOCK, null, 1, user);

        assertThatThrownBy(() -> Budget.copyOf(categoryOnly, household, NOVEMBER, 2, CLOCK, 0, user))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(BudgetErrorCode.BUDGET_LIMIT_REQUIRED));
        assertThat(Budget.copyOf(categoryOnly, household, NOVEMBER, 2, CLOCK, 1, user).overallLimitMinor()).isNull();
    }
}
