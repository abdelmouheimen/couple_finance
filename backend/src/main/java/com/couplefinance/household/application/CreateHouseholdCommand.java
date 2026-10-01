package com.couplefinance.household.application;

import org.jspecify.annotations.Nullable;

/**
 * Input of household creation. Optional settings fall back to {@link HouseholdDefaults}. Deliberately carries no
 * user id: the creator is always the authenticated user.
 */
public record CreateHouseholdCommand(
        String name,
        @Nullable String currency,
        @Nullable String timezone,
        @Nullable Integer periodStartDay) {
}
