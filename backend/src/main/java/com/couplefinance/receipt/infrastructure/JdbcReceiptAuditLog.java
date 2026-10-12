package com.couplefinance.receipt.infrastructure;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptAuditLog;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code receipt.audit_event} rows in the caller's transaction (database-schema.md section 11). The audit
 * describes the evidence (kind, pages, sizes, original hashes) and never stores content, storage keys or URLs.
 * {@code owner_user_id} is the uploader: a draft and its history are private to them (BR-RCP-16).
 */
@Repository
class JdbcReceiptAuditLog implements ReceiptAuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JdbcReceiptAuditLog(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public void uploaded(Receipt receipt, UserId actor) {
        insert(receipt, actor, "UPLOAD", describe(receipt, true));
    }

    @Override
    public void deleted(Receipt receipt, UserId actor) {
        insert(receipt, actor, "DELETE", describe(receipt, false));
    }

    @Override
    public void linked(Receipt receipt, UserId actor, String action) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("old", receipt.status().name());
        status.put("new", "CONFIRM".equals(action) ? "CONFIRMED" : "ATTACHED");
        changes.put("status", status);
        insert(receipt, actor, action, changes);
    }

    private Map<String, Map<String, Object>> describe(Receipt receipt, boolean created) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        put(changes, "sourceKind", receipt.sourceKind().name(), created);
        put(changes, "pageCount", receipt.pageCount(), created);
        put(changes, "totalSizeBytes", receipt.totalSizeBytes(), created);
        put(changes, "originalSha256", receipt.pages().stream()
                .map(p -> HexFormat.of().formatHex(p.originalSha256())).distinct().toList(), created);
        return changes;
    }

    private static void put(Map<String, Map<String, Object>> changes, String field, Object value, boolean created) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("old", created ? null : value);
        change.put("new", created ? value : null);
        changes.put(field, change);
    }

    private void insert(Receipt receipt, UserId actor, String action, Map<String, Map<String, Object>> changes) {
        jdbc.sql("""
                        INSERT INTO receipt.audit_event
                            (id, household_id, entity_type, entity_id, owner_user_id, action, actor_type,
                             actor_user_id, changes, occurred_at)
                        VALUES (:id, :householdId, 'RECEIPT', :entityId, :owner, :action, 'USER', :actor,
                                CAST(:changes AS jsonb), :occurredAt)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("householdId", receipt.householdId().value())
                .param("entityId", receipt.id())
                .param("owner", receipt.uploadedBy().value())
                .param("action", action)
                .param("actor", actor.value())
                .param("changes", jsonMapper.writeValueAsString(changes))
                .param("occurredAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }
}
