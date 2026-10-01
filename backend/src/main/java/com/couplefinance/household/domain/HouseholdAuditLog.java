package com.couplefinance.household.domain;

import java.time.Instant;
import java.util.Map;

import com.couplefinance.shared.id.UserId;

/** Append-only audit trail of the household module (database-schema.md §11). */
public interface HouseholdAuditLog {

    void householdCreated(Household household, UserId actor, Map<String, Object> createdValues, Instant occurredAt);
}
