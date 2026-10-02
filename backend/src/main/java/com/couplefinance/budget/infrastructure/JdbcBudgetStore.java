package com.couplefinance.budget.infrastructure;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.couplefinance.budget.domain.Budget;
import com.couplefinance.budget.domain.BudgetStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code INSERT ... ON CONFLICT (household_id, period_start) DO NOTHING}: concurrent creations of the same
 * period produce exactly one row, and the loser learns it without a failed (transaction-aborting) statement.
 */
@Repository
class JdbcBudgetStore implements BudgetStore {

    private final JdbcClient jdbc;

    JdbcBudgetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(Budget budget) {
        int inserted = jdbc.sql("""
                        INSERT INTO budget.budget
                            (id, household_id, period_start, period_end, currency, overall_limit_minor,
                             created_at, created_by, updated_at, updated_by, version)
                        VALUES (:id, :householdId, :periodStart, :periodEnd, :currency, :overallLimitMinor,
                                :createdAt, :createdBy, :updatedAt, :updatedBy, 0)
                        ON CONFLICT (household_id, period_start) DO NOTHING
                        """)
                .param("id", budget.id())
                .param("householdId", budget.householdId())
                .param("periodStart", budget.periodStart())
                .param("periodEnd", budget.periodEnd())
                .param("currency", budget.currency().value())
                .param("overallLimitMinor", budget.overallLimitMinor())
                .param("createdAt", OffsetDateTime.ofInstant(budget.createdAt(), ZoneOffset.UTC))
                .param("createdBy", budget.createdBy())
                .param("updatedAt", OffsetDateTime.ofInstant(budget.updatedAt(), ZoneOffset.UTC))
                .param("updatedBy", budget.updatedBy())
                .update();
        return inserted == 1;
    }
}
