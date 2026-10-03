package com.couplefinance.budget.infrastructure;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.budget.domain.Budget;
import com.couplefinance.budget.domain.BudgetAuditLog;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code budget.audit_event} rows in the caller's transaction (database-schema.md section 11). Plain JDBC:
 * the table is append-only and {@code changes} is {@code jsonb}. Amounts are minor units plus currency.
 */
@Repository
class JdbcBudgetAuditLog implements BudgetAuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JdbcBudgetAuditLog(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public void created(Budget budget, Map<UUID, Long> categoryLimitsMinor, UserId actor) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        put(changes, "periodStart", null, budget.periodStart().toString());
        put(changes, "periodEnd", null, budget.periodEnd().toString());
        put(changes, "currency", null, budget.currency().value());
        put(changes, "overallLimitMinor", null, budget.overallLimitMinor());
        if (!categoryLimitsMinor.isEmpty()) {
            put(changes, "categoryLimitsMinor", null, categoryLimitsMinor);
        }
        if (budget.copiedFromBudgetId() != null) {
            put(changes, "copiedFromBudgetId", null, budget.copiedFromBudgetId().toString());
        }
        insert(budget, "CREATE", actor, changes, budget.createdAt());
    }

    @Override
    public void overallLimitChanged(Budget budget, Long oldLimitMinor, UserId actor) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        put(changes, "overallLimitMinor", oldLimitMinor, budget.overallLimitMinor());
        insert(budget, "UPDATE", actor, changes, budget.updatedAt());
    }

    @Override
    public void categoryLimitsChanged(Budget budget, Map<UUID, Long> oldMinor, Map<UUID, Long> newMinor,
            UserId actor) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        put(changes, "categoryLimitsMinor", oldMinor, newMinor);
        insert(budget, "UPDATE", actor, changes, budget.updatedAt());
    }

    private void insert(Budget budget, String action, UserId actor, Map<String, Map<String, Object>> changes,
            java.time.Instant occurredAt) {
        jdbc.sql("""
                        INSERT INTO budget.audit_event
                            (id, household_id, entity_type, entity_id, action, actor_type, actor_user_id, changes,
                             occurred_at)
                        VALUES (:id, :householdId, 'BUDGET', :entityId, :action, 'USER', :actor,
                                CAST(:changes AS jsonb), :occurredAt)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("householdId", budget.householdId())
                .param("entityId", budget.id())
                .param("action", action)
                .param("actor", actor.value())
                .param("changes", jsonMapper.writeValueAsString(changes))
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .update();
    }

    private static void put(Map<String, Map<String, Object>> changes, String field, Object oldValue,
            Object newValue) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("old", oldValue);
        change.put("new", newValue);
        changes.put(field, change);
    }
}
