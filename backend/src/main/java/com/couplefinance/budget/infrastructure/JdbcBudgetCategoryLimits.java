package com.couplefinance.budget.infrastructure;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.couplefinance.budget.domain.Budget;
import com.couplefinance.budget.domain.BudgetCategoryLimits;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain JDBC over {@code budget.budget_category}; every statement is scoped by the household of the budget. */
@Repository
class JdbcBudgetCategoryLimits implements BudgetCategoryLimits {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcBudgetCategoryLimits(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<Line> findByBudget(UUID householdId, UUID budgetId) {
        return jdbc.sql("""
                        SELECT category_id, limit_minor FROM budget.budget_category
                        WHERE household_id = :householdId AND budget_id = :budgetId
                        ORDER BY id
                        """)
                .param("householdId", householdId).param("budgetId", budgetId)
                .query((rs, row) -> new Line(rs.getObject("category_id", UUID.class), rs.getLong("limit_minor")))
                .list();
    }

    @Override
    public void insert(Budget budget, UUID categoryId, long limitMinor, UserId actor) {
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO budget.budget_category
                            (id, budget_id, household_id, category_id, limit_minor, created_at, created_by,
                             updated_at, updated_by)
                        VALUES (:id, :budgetId, :householdId, :categoryId, :limit, :now, :actor, :now, :actor)
                        """)
                .param("id", UuidV7.generate(clock)).param("budgetId", budget.id())
                .param("householdId", budget.householdId()).param("categoryId", categoryId)
                .param("limit", limitMinor).param("now", now).param("actor", actor.value()).update();
    }

    @Override
    public void updateLimit(Budget budget, UUID categoryId, long limitMinor, UserId actor) {
        jdbc.sql("""
                        UPDATE budget.budget_category
                        SET limit_minor = :limit, updated_at = :now, updated_by = :actor
                        WHERE household_id = :householdId AND budget_id = :budgetId AND category_id = :categoryId
                        """)
                .param("limit", limitMinor).param("now", now()).param("actor", actor.value())
                .param("householdId", budget.householdId()).param("budgetId", budget.id())
                .param("categoryId", categoryId).update();
    }

    @Override
    public void delete(Budget budget, UUID categoryId) {
        jdbc.sql("""
                        DELETE FROM budget.budget_category
                        WHERE household_id = :householdId AND budget_id = :budgetId AND category_id = :categoryId
                        """)
                .param("householdId", budget.householdId()).param("budgetId", budget.id())
                .param("categoryId", categoryId).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
