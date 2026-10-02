package com.couplefinance.expense.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.expense.application.AuditEntryView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The audit trail of an expense, oldest entry first (BR-EXP-10). */
@Schema(name = "ExpenseAuditTrail", requiredProperties = {"items"})
record ExpenseAuditResponse(List<Entry> items) {

    /** One audit entry. */
    @Schema(name = "ExpenseAuditEntry",
            requiredProperties = {"id", "action", "actorType", "occurredAt", "changes"})
    record Entry(
            UUID id,
            @Schema(description = "CREATE, UPDATE, DELETE, RESTORE, ...") String action,
            @Schema(description = "USER or SYSTEM") String actorType,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable UUID actorUserId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String actorSystem,
            Instant occurredAt,
            @Schema(description = "Field name to {old, new}; amounts are minor units.")
            Map<String, Object> changes) {
    }

    static ExpenseAuditResponse from(List<AuditEntryView> entries) {
        return new ExpenseAuditResponse(entries.stream().map(entry -> new Entry(entry.id(), entry.action(),
                entry.actorType(), entry.actorUserId(), entry.actorSystem(), entry.occurredAt(),
                entry.changes())).toList());
    }
}
