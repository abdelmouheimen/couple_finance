package com.couplefinance.expense.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.expense.domain.ExpenseAuditTrail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reads {@code expense.audit_event} (plain JDBC: append-only table with a {@code jsonb} column). */
@Repository
class JdbcExpenseAuditTrail implements ExpenseAuditTrail {

    private static final TypeReference<Map<String, Object>> CHANGES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    JdbcExpenseAuditTrail(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<Entry> history(UUID expenseId, UUID householdId, UUID userId) {
        // Served by ix_expense_audit_entity (household_id, entity_id, occurred_at DESC).
        return jdbc.sql("""
                        SELECT id, action, actor_type, actor_user_id, actor_system, occurred_at,
                               CAST(changes AS text) AS changes
                        FROM expense.audit_event
                        WHERE household_id = :householdId AND entity_type = 'EXPENSE' AND entity_id = :expenseId
                          AND (owner_user_id IS NULL OR owner_user_id = :userId)
                        ORDER BY occurred_at, id
                        """)
                .param("householdId", householdId)
                .param("expenseId", expenseId)
                .param("userId", userId)
                .query((rs, row) -> new Entry(
                        rs.getObject("id", UUID.class),
                        rs.getString("action"),
                        rs.getString("actor_type"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_system"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        jsonMapper.readValue(rs.getString("changes"), CHANGES)))
                .list();
    }
}
