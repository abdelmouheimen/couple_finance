package com.couplefinance.household.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import com.couplefinance.household.domain.Household;
import com.couplefinance.household.domain.HouseholdAuditLog;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code household.audit_event} rows in the caller's transaction (database-schema.md §11). Plain JDBC: the
 * table is append-only and {@code changes} is {@code jsonb}.
 */
@Repository
class JdbcHouseholdAuditLog implements HouseholdAuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JdbcHouseholdAuditLog(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public void householdCreated(Household household, UserId actor, Map<String, Object> createdValues,
                                 Instant occurredAt) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        createdValues.forEach((field, value) -> {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("old", null);
            change.put("new", value);
            changes.put(field, change);
        });
        jdbc.sql("""
                        INSERT INTO household.audit_event
                            (id, household_id, entity_type, entity_id, action, actor_type, actor_user_id, changes,
                             occurred_at)
                        VALUES (:id, :householdId, 'HOUSEHOLD', :householdId, 'CREATE', 'USER', :actor,
                                CAST(:changes AS jsonb), :occurredAt)
                        """)
                .param("id", UuidV7.generate(clock))
                .param("householdId", household.id().value())
                .param("actor", actor.value())
                .param("changes", jsonMapper.writeValueAsString(changes))
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .update();
    }
}
