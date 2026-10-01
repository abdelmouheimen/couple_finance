package com.couplefinance.household.api;

import java.util.Objects;

import com.couplefinance.shared.id.HouseholdId;

/**
 * Household context of a background job (security.md §4.4), built from the stored {@code household_id}. Audit
 * actor is {@code SYSTEM:<reason>}. Never carries a user identity.
 *
 * @param reason the job name, e.g. {@code BUDGET_PERIOD_EXTENSION}
 */
public record SystemHouseholdContext(HouseholdId householdId, String reason) {

    public SystemHouseholdContext {
        Objects.requireNonNull(householdId, "householdId");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
