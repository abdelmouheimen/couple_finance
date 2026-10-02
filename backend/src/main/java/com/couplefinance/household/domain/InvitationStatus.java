package com.couplefinance.household.domain;

/** Lifecycle of an invitation (database-schema.md 5.6). Redemption (REDEEMED) belongs to a later Issue. */
public enum InvitationStatus { ACTIVE, REDEEMED, REVOKED, EXPIRED }
