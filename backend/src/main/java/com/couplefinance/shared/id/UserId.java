package com.couplefinance.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a user account, referenced by every module that records who did what. */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
