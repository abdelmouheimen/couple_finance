package com.couplefinance.identity.domain;

/** Account lifecycle (domain-model.md §3). */
public enum UserAccountStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    DELETED
}
