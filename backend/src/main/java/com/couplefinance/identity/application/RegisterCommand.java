package com.couplefinance.identity.application;

import org.jspecify.annotations.Nullable;

/** Registration input; {@code locale} is {@code null} when the client supplies none. Never print the password. */
public record RegisterCommand(String email, String password, String displayName, @Nullable String locale) {

    @Override
    public String toString() {
        return "RegisterCommand[]";
    }
}
