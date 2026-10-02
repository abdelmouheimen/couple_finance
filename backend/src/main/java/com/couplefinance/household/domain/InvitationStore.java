package com.couplefinance.household.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;

/** Persistence of {@code household.invitation}. Every method is scoped by household. */
public interface InvitationStore {

    enum RevokeResult { REVOKED, ALREADY_REVOKED, NOT_REVOCABLE, NOT_FOUND }

    /**
     * Locks the household row ({@code FOR UPDATE}) and reads its status and active member count, serialising
     * concurrent invitation creation (and later joins) for this household.
     */
    Optional<Invitation.HouseholdSeats> lockSeats(HouseholdId householdId);

    /** Revokes every ACTIVE invitation of the household; returns how many. */
    int revokeAllActive(HouseholdId householdId, Instant now);

    void insert(Invitation invitation, byte[] codeHash);

    /** ACTIVE, unexpired invitations created by the caller, most recent first. */
    List<Invitation> findActive(HouseholdId householdId, UserId creator, Instant now);

    /** Revokes an invitation of the household created by {@code creator}; anything else is NOT_FOUND. */
    RevokeResult revoke(UUID id, HouseholdId householdId, UserId creator, Instant now);
}
