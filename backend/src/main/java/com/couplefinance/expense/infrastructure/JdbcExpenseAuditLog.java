package com.couplefinance.expense.infrastructure;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseAuditLog;
import com.couplefinance.expense.domain.ExpenseItem;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code expense.audit_event} rows in the caller's transaction (database-schema.md section 11). Plain JDBC:
 * the table is append-only and {@code changes} is {@code jsonb}. The row mirrors the expense visibility in
 * {@code owner_user_id} (BR-EXP-10: the audit of a PERSONAL expense is visible to its owner only).
 */
@Repository
class JdbcExpenseAuditLog implements ExpenseAuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JdbcExpenseAuditLog(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public void created(Expense expense, UserId actor) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        put(changes, "kind", expense.kind().name());
        put(changes, "amountMinor", expense.amountMinor());
        put(changes, "currency", expense.currency().value());
        put(changes, "expenseDate", expense.expenseDate().toString());
        put(changes, "paidByUserId", expense.paidByUserId().toString());
        put(changes, "ownerUserId", expense.ownerUserId() == null ? null : expense.ownerUserId().toString());
        put(changes, "merchant", expense.merchantDisplay());
        put(changes, "note", expense.note());
        put(changes, "refundOfExpenseId",
                expense.refundOfExpenseId() == null ? null : expense.refundOfExpenseId().toString());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExpenseItem item : expense.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("categoryId", item.categoryId().toString());
            entry.put("amountMinor", item.amountMinor());
            entry.put("label", item.label());
            items.add(entry);
        }
        put(changes, "items", items);
        jdbc.sql("""
                        INSERT INTO expense.audit_event
                            (id, household_id, entity_type, entity_id, owner_user_id, action, actor_type,
                             actor_user_id, changes, occurred_at)
                        VALUES (:id, :householdId, 'EXPENSE', :entityId, :ownerUserId, 'CREATE', 'USER', :actor,
                                CAST(:changes AS jsonb), :occurredAt)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("householdId", expense.householdId())
                .param("entityId", expense.id())
                .param("ownerUserId", expense.ownerUserId())
                .param("actor", actor.value())
                .param("changes", jsonMapper.writeValueAsString(changes))
                .param("occurredAt", OffsetDateTime.ofInstant(expense.createdAt(), ZoneOffset.UTC))
                .update();
    }

    private static void put(Map<String, Map<String, Object>> changes, String field, Object value) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("old", null);
        change.put("new", value);
        changes.put(field, change);
    }
}
