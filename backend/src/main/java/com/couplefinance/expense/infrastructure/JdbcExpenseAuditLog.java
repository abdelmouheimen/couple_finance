package com.couplefinance.expense.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseAuditLog;
import com.couplefinance.expense.domain.ExpenseItem;
import com.couplefinance.expense.domain.ExpenseSnapshot;
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

    @Override
    public void updated(ExpenseSnapshot before, Expense after, UserId actor) {
        ExpenseSnapshot now = after.snapshot();
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        diff(changes, "amountMinor", before.amountMinor(), now.amountMinor());
        diff(changes, "expenseDate", before.expenseDate().toString(), now.expenseDate().toString());
        diff(changes, "paidByUserId", before.paidByUserId().toString(), now.paidByUserId().toString());
        diff(changes, "ownerUserId", str(before.ownerUserId()), str(now.ownerUserId()));
        diff(changes, "merchant", before.merchant(), now.merchant());
        diff(changes, "note", before.note(), now.note());
        diff(changes, "items", itemMaps(before.items()), itemMaps(now.items()));
        // BR-EXP-10: an entry that touches a PERSONAL expense (before or after) is visible to its owner only.
        UUID owner = before.ownerUserId() != null ? before.ownerUserId() : now.ownerUserId();
        jdbc.sql("""
                        INSERT INTO expense.audit_event
                            (id, household_id, entity_type, entity_id, owner_user_id, action, actor_type,
                             actor_user_id, changes, occurred_at)
                        VALUES (:id, :householdId, 'EXPENSE', :entityId, :ownerUserId, 'UPDATE', 'USER', :actor,
                                CAST(:changes AS jsonb), :occurredAt)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("householdId", after.householdId())
                .param("entityId", after.id())
                .param("ownerUserId", owner)
                .param("actor", actor.value())
                .param("changes", jsonMapper.writeValueAsString(changes))
                .param("occurredAt", OffsetDateTime.ofInstant(after.updatedAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public void deleted(Expense expense, UserId actor) {
        insertLifecycle(expense, actor, "DELETE", null, expense.deletedAt().toString(), expense.deletedAt());
    }

    @Override
    public void restored(Expense expense, UserId actor, Instant deletedAt) {
        insertLifecycle(expense, actor, "RESTORE", deletedAt.toString(), null, expense.updatedAt());
    }

    private void insertLifecycle(Expense expense, UserId actor, String action, String oldDeletedAt,
            String newDeletedAt, Instant occurredAt) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("old", oldDeletedAt);
        change.put("new", newDeletedAt);
        changes.put("deletedAt", change);
        jdbc.sql("""
                        INSERT INTO expense.audit_event
                            (id, household_id, entity_type, entity_id, owner_user_id, action, actor_type,
                             actor_user_id, changes, occurred_at)
                        VALUES (:id, :householdId, 'EXPENSE', :entityId, :ownerUserId, :action, 'USER', :actor,
                                CAST(:changes AS jsonb), :occurredAt)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("householdId", expense.householdId())
                .param("entityId", expense.id())
                .param("ownerUserId", expense.ownerUserId())
                .param("action", action)
                .param("actor", actor.value())
                .param("changes", jsonMapper.writeValueAsString(changes))
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .update();
    }

    private static void diff(Map<String, Map<String, Object>> changes, String field, Object oldValue,
            Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("old", oldValue);
            change.put("new", newValue);
            changes.put(field, change);
        }
    }

    private static String str(UUID value) {
        return value == null ? null : value.toString();
    }

    private static List<Map<String, Object>> itemMaps(List<ExpenseSnapshot.Item> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ExpenseSnapshot.Item item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("categoryId", item.categoryId().toString());
            entry.put("amountMinor", item.amountMinor());
            entry.put("label", item.label());
            result.add(entry);
        }
        return result;
    }

    private static void put(Map<String, Map<String, Object>> changes, String field, Object value) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("old", null);
        change.put("new", value);
        changes.put(field, change);
    }
}
