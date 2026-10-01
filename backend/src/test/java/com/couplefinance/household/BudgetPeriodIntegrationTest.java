package com.couplefinance.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.household.api.BudgetPeriods;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.application.BudgetPeriodExtensionJob;
import com.couplefinance.household.application.CreateHouseholdCommand;
import com.couplefinance.household.application.CreateHouseholdService;
import com.couplefinance.household.domain.Household;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Budget period calendar: persistence constraints, creation flow, extension job and facade isolation. */
@IntegrationTest
class BudgetPeriodIntegrationTest {

    @Autowired
    CreateHouseholdService createHousehold;

    @Autowired
    BudgetPeriods budgetPeriods;

    @Autowired
    BudgetPeriodExtensionJob extensionJob;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Test
    void BR_HH_05_creation_generates_a_contiguous_calendar_whose_first_period_contains_today() {
        Household household = create(15);
        HouseholdContext context = contextOf(household);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Paris")));

        List<BudgetPeriod> periods = budgetPeriods.listPeriods(context, today.minusMonths(2), today.plusYears(5));

        assertThat(periods).hasSizeBetween(25, 26);
        assertThat(periods.getFirst().contains(today)).isTrue();
        assertThat(periods.getFirst().start().getDayOfMonth()).isEqualTo(15);
        for (int i = 1; i < periods.size(); i++) {
            assertThat(periods.get(i).start()).isEqualTo(periods.get(i - 1).end());
        }
        assertThat(budgetPeriods.findPeriodContaining(context, today)).contains(periods.getFirst());
    }

    @Test
    void facade_returns_empty_for_a_date_outside_the_calendar() {
        HouseholdContext context = contextOf(create(1));

        assertThat(budgetPeriods.findPeriodContaining(context, LocalDate.of(1990, 1, 1))).isEmpty();
        assertThat(budgetPeriods.findPeriodContaining(context, LocalDate.now(clock).plusYears(10))).isEmpty();
    }

    @Test
    void BR_HH_07_extension_job_restores_missing_periods_without_altering_stored_ones() {
        Household household = create(1);
        UUID id = household.id().value();
        List<BudgetPeriod> before = stored(id);
        jdbc.sql("""
                DELETE FROM household.budget_period WHERE household_id = :id
                  AND period_start > (SELECT min(period_start) FROM household.budget_period WHERE household_id = :id)
                      + 100
                """).param("id", id).update();
        assertThat(stored(id)).hasSizeLessThan(before.size());

        extensionJob.extendCalendars();
        List<BudgetPeriod> afterFirstRun = stored(id);
        extensionJob.extendCalendars();

        assertThat(afterFirstRun).isEqualTo(before);
        assertThat(stored(id)).isEqualTo(before);
    }

    @Test
    void extension_job_does_not_touch_a_dissolved_household() {
        Household household = create(1);
        UUID id = household.id().value();
        jdbc.sql("DELETE FROM household.budget_period WHERE household_id = :id").param("id", id).update();
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = now(), purge_at = now() "
                + "WHERE id = :id").param("id", id).update();

        extensionJob.extendCalendars();

        assertThat(stored(id)).isEmpty();
    }

    @Test
    void facade_lookups_never_return_periods_of_another_household() {
        Household first = create(1);
        Household second = create(15);
        HouseholdContext firstContext = contextOf(first);
        LocalDate today = LocalDate.now(clock);

        assertThat(budgetPeriods.listPeriods(firstContext, today.minusYears(1), today.plusYears(5)))
                .isEqualTo(stored(first.id().value()));
        assertThat(budgetPeriods.listPeriods(contextOf(second), today.minusYears(1), today.plusYears(5)))
                .isEqualTo(stored(second.id().value()))
                .allSatisfy(p -> assertThat(p.start().getDayOfMonth()).isEqualTo(15));
        assertThat(budgetPeriods.findPeriodContaining(firstContext, today))
                .hasValueSatisfying(p -> assertThat(p.start().getDayOfMonth()).isEqualTo(1));
        // A context for a household without periods sees nothing, whatever the other households hold.
        HouseholdContext unknown = new HouseholdContext(new HouseholdId(UUID.randomUUID()),
                firstContext.userId(), HouseholdContext.Status.ACTIVE, HouseholdContext.Role.MEMBER);
        assertThat(budgetPeriods.findPeriodContaining(unknown, today)).isEmpty();
        assertThat(budgetPeriods.listPeriods(unknown, today.minusYears(1), today.plusYears(5))).isEmpty();
    }

    @Test
    void overlapping_periods_are_rejected_by_the_database() {
        UUID id = create(1).id().value();

        insert(id, LocalDate.of(1999, 1, 1), LocalDate.of(1999, 2, 1));
        assertThatThrownBy(() -> insert(id, LocalDate.of(1999, 1, 20), LocalDate.of(1999, 2, 20)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_budget_period_no_overlap");
    }

    @Test
    void periods_outside_15_to_62_days_are_rejected_by_the_database() {
        UUID id = create(1).id().value();

        assertThatThrownBy(() -> insert(id, LocalDate.of(1998, 1, 1), LocalDate.of(1998, 1, 15)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_budget_period_length");
        assertThatThrownBy(() -> insert(id, LocalDate.of(1998, 1, 1), LocalDate.of(1998, 3, 5)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_budget_period_length");
        assertThatThrownBy(() -> insert(id, LocalDate.of(1998, 2, 1), LocalDate.of(1998, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_period_requires_an_existing_household() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), LocalDate.of(1997, 1, 1), LocalDate.of(1997, 2, 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_budget_period_household");
    }

    private Household create(int startDay) {
        return createHousehold.create(new UserId(users.active()),
                new CreateHouseholdCommand("Foyer", "EUR", "Europe/Paris", startDay));
    }

    private static HouseholdContext contextOf(Household household) {
        return new HouseholdContext(household.id(), new UserId(UUID.randomUUID()), HouseholdContext.Status.ACTIVE,
                HouseholdContext.Role.MEMBER);
    }

    private List<BudgetPeriod> stored(UUID householdId) {
        return jdbc.sql("SELECT period_start, period_end FROM household.budget_period "
                        + "WHERE household_id = :id ORDER BY period_start")
                .param("id", householdId)
                .query((rs, row) -> new BudgetPeriod(rs.getDate(1).toLocalDate(), rs.getDate(2).toLocalDate()))
                .list();
    }

    private void insert(UUID householdId, LocalDate start, LocalDate end) {
        jdbc.sql("INSERT INTO household.budget_period (household_id, period_start, period_end) "
                        + "VALUES (:id, :start, :end)")
                .param("id", householdId)
                .param("start", Date.valueOf(start))
                .param("end", Date.valueOf(end))
                .update();
    }
}
