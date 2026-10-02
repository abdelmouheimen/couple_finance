package com.couplefinance.budget.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Database-level guarantees of the budget schema, exercised with raw SQL that bypasses the domain model:
 * BR-BUD-01 (one budget per period), BR-HH-07 (the period is a real calendar period), BR-MON-07 (household
 * currency, strictly positive limit), household isolation by composite foreign keys.
 */
@IntegrationTest
class BudgetConstraintsIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 1);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestUsers users;

    private record Fixture(UUID household, UUID member) {
    }

    private Fixture household(String currency) {
        UUID member = users.active();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO household.household (id, name, currency, timezone, status, tracking_start_date,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (:id, 'H', :currency, 'Europe/Paris', 'ACTIVE', current_date, :now, :user, :now, :user)
                        """).param("id", id).param("currency", currency).param("now", now).param("user", member)
                .update();
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:h, :u, 1, :now)").param("h", id).param("u", member).param("now", now).update();
        jdbc.sql("INSERT INTO household.budget_period (household_id, period_start, period_end) "
                + "VALUES (:h, :s, :e)").param("h", id).param("s", START).param("e", END).update();
        return new Fixture(id, member);
    }

    private void insertBudget(Fixture f, LocalDate start, LocalDate end, String currency, Long limitMinor) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO budget.budget (id, household_id, period_start, period_end, currency,
                            overall_limit_minor, created_at, created_by, updated_at, updated_by)
                        VALUES (:id, :h, :s, :e, :currency, :limit, :now, :user, :now, :user)
                        """).param("id", UUID.randomUUID()).param("h", f.household()).param("s", start)
                .param("e", end).param("currency", currency).param("limit", limitMinor, java.sql.Types.BIGINT)
                .param("now", now).param("user", f.member()).update();
    }

    @Test
    void BR_BUD_01_one_budget_per_household_and_period() {
        Fixture f = household("EUR");
        insertBudget(f, START, END, "EUR", 100L);

        assertThatThrownBy(() -> insertBudget(f, START, END, "EUR", 200L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_budget_period");
    }

    @Test
    void BR_HH_07_the_budget_period_must_be_a_real_calendar_period_with_the_matching_end() {
        Fixture f = household("EUR");

        assertThatThrownBy(() -> insertBudget(f, START, END.plusDays(1), "EUR", 100L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_budget_period");
        assertThatThrownBy(() -> insertBudget(f, START.plusDays(1), END, "EUR", 100L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_budget_period");
    }

    @Test
    void a_budget_cannot_use_the_period_of_another_household() {
        Fixture a = household("EUR");
        Fixture b = household("EUR");
        jdbc.sql("DELETE FROM household.budget_period WHERE household_id = :h").param("h", b.household()).update();

        assertThatThrownBy(() -> insertBudget(b, START, END, "EUR", 100L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_budget_period");
        insertBudget(a, START, END, "EUR", 100L);
    }

    @Test
    void BR_MON_07_the_currency_is_the_household_currency() {
        Fixture f = household("EUR");

        assertThatThrownBy(() -> insertBudget(f, START, END, "USD", 100L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_budget_currency");
    }

    @Test
    void BR_MON_07_the_overall_limit_is_between_1_and_10_pow_15_minor_units_or_null() {
        Fixture f = household("EUR");

        assertThatThrownBy(() -> insertBudget(f, START, END, "EUR", 0L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_budget_overall_limit");
        assertThatThrownBy(() -> insertBudget(f, START, END, "EUR", -1L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_budget_overall_limit");
        assertThatThrownBy(() -> insertBudget(f, START, END, "EUR", 1_000_000_000_000_001L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_budget_overall_limit");
        // nullable by design: "at least one limit" spans the category table (BR-BUD-02, application rule)
        insertBudget(f, START, END, "EUR", null);
        assertThat(jdbc.sql("SELECT count(*) FROM budget.budget WHERE household_id = :h")
                .param("h", f.household()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void the_creator_must_be_a_member_of_the_household() {
        Fixture f = household("EUR");
        Fixture foreign = household("EUR");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO budget.budget (id, household_id, period_start, period_end, currency,
                            overall_limit_minor, created_at, created_by, updated_at, updated_by)
                        VALUES (:id, :h, :s, :e, 'EUR', 100, :now, :user, :now, :user)
                        """).param("id", UUID.randomUUID()).param("h", f.household()).param("s", START)
                .param("e", END).param("now", now).param("user", foreign.member()).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_budget_created_by");
    }
}
