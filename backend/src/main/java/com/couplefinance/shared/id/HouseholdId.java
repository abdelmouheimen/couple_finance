package com.couplefinance.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a household, carried by every household-owned row (database-schema.md §3). */
public record HouseholdId(UUID value) {

    public HouseholdId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
