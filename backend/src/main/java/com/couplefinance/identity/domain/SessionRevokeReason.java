package com.couplefinance.identity.domain;

/** Why a session was revoked (database-schema.md §4.2). */
public enum SessionRevokeReason {
    LOGOUT, LOGOUT_ALL, PASSWORD_RESET, REUSE_DETECTED, ACCOUNT_DELETED
}
