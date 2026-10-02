package com.couplefinance.expense.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** One audit entry of an expense (BR-EXP-10): who, when, and the old/new value of each changed field. */
public record AuditEntryView(UUID id, String action, String actorType, @Nullable UUID actorUserId,
        @Nullable String actorSystem, Instant occurredAt, Map<String, Object> changes) {}
