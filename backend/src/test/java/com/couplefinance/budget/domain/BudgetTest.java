package com.couplefinance.budget.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;

/** Domain rules of the budget aggregate (Issue #24): BR-BUD-01, BR-BUD-02, BR-BUD-07, BR-MON-02/03/07. */
class BudgetTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);
    private static final BudgetPeriod PERIOD = new BudgetPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));

    private final HouseholdId household = new HouseholdId(UUID.randomUUID());
    private final UserId user = new UserId(UUID.randomUUID());

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private Budget budget(String limit) {
        return Budget.create(household, PERIOD, EUR, 2, CLOCK, eur(limit), user);
    }

    @Test
    void BR_BUD_01_one_budget_per_period_the_budget_is_keyed_by_the_household_and_the_calendar_period() {
        Budget budget = budget("1500.00");

        assertThat(budget.householdId()).isEqualTo(household.value());
        assertThat(budget.periodStart()).isEqualTo(PERIOD.start());
        assertThat(budget.periodEnd()).isEqualTo(PERIOD.end()); // BR-HH-07: end copied from the calendar
        assertThat(budget.overallLimitMinor()).isEqualTo(150_000L);
        assertThat(budget.currency()).isEqualTo(EUR);
        assertThat(budget.version()).isZero();
    }

    @Test
    void BR_BUD_02_budget_needs_at_least_one_limit_creation_without_a_limit_is_rejected() {
        assertThatThrownBy(() -> Budget.create(household, PERIOD, EUR, 2, CLOCK, null, user))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(BudgetErrorCode.BUDGET_LIMIT_REQUIRED));
    }

    @Test
    void BR_BUD_02_budget_needs_at_least_one_limit_the_limit_cannot_be_removed_by_an_update() {
        Budget budget = budget("1500.00");

        assertThatThrownBy(() -> budget.setOverallLimit(null, 2, CLOCK, user))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(BudgetErrorCode.BUDGET_LIMIT_REQUIRED));
        assertThat(budget.overallLimitMinor()).isEqualTo(150_000L);
    }

    @Test
    void BR_MON_07_the_limit_is_strictly_positive() {
        assertThatThrownBy(() -> budget("0.00")).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo("AMOUNT_NOT_POSITIVE"));
        assertThatThrownBy(() -> budget("-5.00")).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo("AMOUNT_NOT_POSITIVE"));
    }

    @Test
    void BR_MON_07_the_limit_is_at_most_the_storable_maximum() {
        assertThat(budget("10000000000000.00").overallLimitMinor()).isEqualTo(Budget.MAX_LIMIT_MINOR);
        assertThatThrownBy(() -> budget("10000000000000.01")).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.errorCode().code()).isEqualTo("AMOUNT_EXCEEDS_MAXIMUM"));
    }

    @Test
    void BR_MON_07_the_limit_is_in_the_household_currency() {
        Money usd = Money.parse("10.00", new CurrencyCode("USD"), 2);

        assertThatThrownBy(() -> Budget.create(household, PERIOD, EUR, 2, CLOCK, usd, user))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("CURRENCY_MISMATCH"));
    }

    @Test
    void BR_BUD_07_an_update_replaces_the_limit_and_records_the_editor() {
        Budget budget = budget("1500.00");
        UserId partner = new UserId(UUID.randomUUID());
        Clock later = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

        boolean changed = budget.setOverallLimit(eur("1800.50"), 2, later, partner);

        assertThat(changed).isTrue();
        assertThat(budget.overallLimitMinor()).isEqualTo(180_050L);
        assertThat(budget.updatedBy()).isEqualTo(partner.value());
        assertThat(budget.updatedAt()).isEqualTo(Instant.parse("2026-10-05T10:00:00Z"));
        assertThat(budget.createdBy()).isEqualTo(user.value());
    }

    @Test
    void BR_BUD_07_past_period_edit_is_audited() {
        // the clock is long after the period ended: the edit is still allowed and the calendar bounds are kept
        Budget budget = budget("1500.00");
        Clock muchLater = Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneOffset.UTC);
        UserId editor = new UserId(UUID.randomUUID());

        boolean changed = budget.setOverallLimit(eur("1600.00"), 2, muchLater, editor);

        assertThat(changed).isTrue(); // the caller then writes the audit entry with old/new values
        assertThat(budget.overallLimitMinor()).isEqualTo(160_000L);
        assertThat(budget.updatedBy()).isEqualTo(editor.value());
        assertThat(budget.periodStart()).isEqualTo(PERIOD.start());
        assertThat(budget.periodEnd()).isEqualTo(PERIOD.end());
    }

    @Test
    void setting_the_same_limit_changes_nothing() {
        Budget budget = budget("1500.00");
        Instant updatedAt = budget.updatedAt();
        Clock later = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

        assertThat(budget.setOverallLimit(eur("1500.00"), 2, later, new UserId(UUID.randomUUID()))).isFalse();
        assertThat(budget.updatedAt()).isEqualTo(updatedAt);
        assertThat(budget.updatedBy()).isEqualTo(user.value());
    }

    @Test
    void a_failed_update_leaves_the_budget_untouched() {
        Budget budget = budget("1500.00");

        assertThatThrownBy(() -> budget.setOverallLimit(eur("0.00"), 2, CLOCK, user))
                .isInstanceOf(ApplicationException.class);
        assertThat(budget.overallLimitMinor()).isEqualTo(150_000L);
    }
}
