package com.couplefinance.identity.application;

import org.jspecify.annotations.Nullable;

public record LoginCommand(String email, String password, @Nullable String deviceLabel) {

    /** Never print the credentials. */
    @Override
    public String toString() {
        return "LoginCommand[]";
    }
}
