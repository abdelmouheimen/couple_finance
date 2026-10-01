package com.couplefinance.household.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.household.domain.BudgetPeriodStore;
import com.couplefinance.shared.id.HouseholdId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBudgetPeriodStore implements BudgetPeriodStore {

    private final JdbcClient jdbc;

    JdbcBudgetPeriodStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BudgetPeriod> findLast(HouseholdId householdId) {
        return jdbc.sql("""
                        SELECT period_start, period_end FROM household.budget_period
                        WHERE household_id = :householdId ORDER BY period_start DESC LIMIT 1
                        """)
                .param("householdId", householdId.value())
                .query((rs, row) -> period(rs.getDate(1), rs.getDate(2)))
                .optional();
    }

    @Override
    public Optional<BudgetPeriod> findContaining(HouseholdId householdId, LocalDate date) {
        return jdbc.sql("""
                        SELECT period_start, period_end FROM household.budget_period
                        WHERE household_id = :householdId AND period_start <= :date AND period_end > :date
                        ORDER BY period_start DESC LIMIT 1
                        """)
                .param("householdId", householdId.value())
                .param("date", Date.valueOf(date))
                .query((rs, row) -> period(rs.getDate(1), rs.getDate(2)))
                .optional();
    }

    @Override
    public List<BudgetPeriod> findIntersecting(HouseholdId householdId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT period_start, period_end FROM household.budget_period
                        WHERE household_id = :householdId AND period_start < :to AND period_end > :from
                        ORDER BY period_start
                        """)
                .param("householdId", householdId.value())
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, row) -> period(rs.getDate(1), rs.getDate(2)))
                .list();
    }

    @Override
    public void insertMissing(HouseholdId householdId, List<BudgetPeriod> periods) {
        for (BudgetPeriod period : periods) {
            jdbc.sql("""
                            INSERT INTO household.budget_period (household_id, period_start, period_end)
                            VALUES (:householdId, :start, :end)
                            ON CONFLICT (household_id, period_start) DO NOTHING
                            """)
                    .param("householdId", householdId.value())
                    .param("start", Date.valueOf(period.start()))
                    .param("end", Date.valueOf(period.end()))
                    .update();
        }
    }

    @Override
    public List<CalendarTarget> findActiveCalendarTargets() {
        return jdbc.sql("""
                        SELECT h.id, h.timezone,
                               (SELECT r.start_day FROM household.period_rule r
                                WHERE r.household_id = h.id ORDER BY r.effective_from DESC LIMIT 1)
                        FROM household.household h WHERE h.status = 'ACTIVE' ORDER BY h.id
                        """)
                .query((rs, row) -> new CalendarTarget(new HouseholdId(rs.getObject(1, UUID.class)),
                        ZoneId.of(rs.getString(2)), rs.getInt(3)))
                .list();
    }

    private static BudgetPeriod period(Date start, Date end) {
        return new BudgetPeriod(start.toLocalDate(), end.toLocalDate());
    }
}
