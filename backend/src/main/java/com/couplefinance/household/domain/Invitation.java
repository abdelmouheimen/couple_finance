package com.couplefinance.household.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;

/**
 * An invitation to join a household (BR-HH-04). The code hash is deliberately not part of this view: it never
 * leaves persistence.
 */
public record Invitation(UUID id, HouseholdId householdId, UserId createdBy, Instant createdAt, Instant expiresAt,
                         InvitationStatus status) {

    /** BR-HH-04: an invitation is valid for 7 days. */
    public static final Duration VALIDITY = Duration.ofDays(7);

    public Invitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
    }

    /**
     * A new ACTIVE invitation. BR-HH-01 / BR-HH-04 / BR-HH-10: only an ACTIVE household with exactly one active
     * member (a free seat) can invite.
     *
     * @throws IllegalStateException when the household cannot invite
     */
    public static Invitation issue(UUID id, HouseholdSeats seats, UserId creator, Instant now) {
        if (!seats.canInvite()) {
            throw new IllegalStateException("Household cannot invite: active=" + seats.active()
                    + ", activeMembers=" + seats.activeMembers());
        }
        return new Invitation(id, seats.householdId(), creator, now, now.plus(VALIDITY), InvitationStatus.ACTIVE);
    }

    /** Whether the invitation can still be redeemed at {@code now}: ACTIVE and not expired. */
    public boolean isUsable(Instant now) {
        return status == InvitationStatus.ACTIVE && now.isBefore(expiresAt);
    }

    /** State of a household relevant to inviting, read under a row lock. */
    public record HouseholdSeats(HouseholdId householdId, boolean active, int activeMembers) {

        public boolean canInvite() {
            return active && activeMembers == 1;
        }
    }
}
