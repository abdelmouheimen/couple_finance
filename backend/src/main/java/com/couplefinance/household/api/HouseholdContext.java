package com.couplefinance.household.api;

import java.util.Objects;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;

/**
 * The household the authenticated user acts in (security.md §4.1). Resolved server-side from the authenticated
 * user — never from a client-supplied id. Every use case on household data receives one and scopes its
 * repository queries with {@link #householdId()}.
 *
 * <p>Both members have identical rights (BR-HH-03): {@link Role} only distinguishes an active member from a
 * former member reading a dissolved household's archive (BR-HH-10).
 *
 * @param status lifecycle state of the household
 * @param role   {@code MEMBER} for an active member, {@code ARCHIVE_READER} for a former member
 */
public record HouseholdContext(HouseholdId householdId, UserId userId, Status status, Role role) {

    /** Lifecycle states visible to other modules (a {@code DELETED} household has no context). */
    public enum Status { ACTIVE, DISSOLVED }

    public enum Role { MEMBER, ARCHIVE_READER }

    public HouseholdContext {
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(role, "role");
    }

    /** Whether the household accepts writes: it is {@code ACTIVE} and the caller is an active member. */
    public boolean isWritable() {
        return status == Status.ACTIVE && role == Role.MEMBER;
    }

    /**
     * Guard every use case that writes household data with this call.
     *
     * @throws ApplicationException {@code HOUSEHOLD_READ_ONLY} (BR-HH-10) when the household is dissolved
     */
    public void requireWritable() {
        if (!isWritable()) {
            throw new ApplicationException(HouseholdAccessErrorCode.HOUSEHOLD_READ_ONLY,
                    "The household is read-only.");
        }
    }
}
