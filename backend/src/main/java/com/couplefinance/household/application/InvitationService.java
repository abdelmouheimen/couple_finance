package com.couplefinance.household.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.domain.Invitation;
import com.couplefinance.household.domain.InvitationCode;
import com.couplefinance.household.domain.InvitationStore;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UuidV7;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, lists and revokes the invitations of the caller's household (BR-HH-04). The household always comes
 * from the authenticated user ({@link CurrentHousehold}), never from the request.
 *
 * <p>Creating an invitation while another one is pending <em>replaces</em> it: the pending invitations are
 * revoked in the same transaction. The code cannot be shown again, so a member who lost it must be able to
 * obtain a new one without first listing and revoking.
 */
@Service
public class InvitationService {

    private final CurrentHousehold currentHousehold;
    private final InvitationStore invitations;
    private final SecureRandom random;
    private final Clock clock;

    @Autowired
    InvitationService(CurrentHousehold currentHousehold, InvitationStore invitations, Clock clock) {
        this(currentHousehold, invitations, new SecureRandom(), clock);
    }

    InvitationService(CurrentHousehold currentHousehold, InvitationStore invitations, SecureRandom random,
                      Clock clock) {
        this.currentHousehold = currentHousehold;
        this.invitations = invitations;
        this.random = random;
        this.clock = clock;
    }

    /** A freshly created invitation and its one-time plaintext code. */
    public record CreatedInvitation(Invitation invitation, InvitationCode code) {}

    @Transactional
    public CreatedInvitation create() {
        HouseholdContext context = writableContext();
        Instant now = clock.instant();
        // Row lock: serialises concurrent creations and keeps the member count stable while we decide.
        Invitation.HouseholdSeats seats = invitations.lockSeats(context.householdId())
                .orElseThrow(InvitationService::notAllowed);
        if (!seats.canInvite()) {
            throw notAllowed();
        }
        invitations.revokeAllActive(context.householdId(), now);
        Invitation invitation = Invitation.issue(UuidV7.generate(clock), seats, context.userId(), now);
        InvitationCode code = InvitationCode.generate(random);
        invitations.insert(invitation, code.hash());
        return new CreatedInvitation(invitation, code);
    }

    @Transactional(readOnly = true)
    public List<Invitation> listActive() {
        HouseholdContext context = writableContext();
        return invitations.findActive(context.householdId(), context.userId(), clock.instant());
    }

    /** Revoking an already revoked invitation succeeds (idempotent). */
    @Transactional
    public void revoke(UUID invitationId) {
        HouseholdContext context = writableContext();
        switch (invitations.revoke(invitationId, context.householdId(), context.userId(), clock.instant())) {
            case REVOKED, ALREADY_REVOKED -> { }
            case NOT_REVOCABLE -> throw new ApplicationException(HouseholdErrorCode.INVITATION_NOT_REVOCABLE,
                    "The invitation can no longer be revoked.");
            case NOT_FOUND -> throw new ApplicationException(HouseholdErrorCode.INVITATION_NOT_FOUND,
                    "Invitation not found.");
        }
    }

    /** Active member of an ACTIVE household; archive readers get HOUSEHOLD_READ_ONLY (BR-HH-10). */
    private HouseholdContext writableContext() {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable();
        return context;
    }

    private static ApplicationException notAllowed() {
        return new ApplicationException(HouseholdErrorCode.INVITATION_NOT_ALLOWED,
                "Only an active household with a single member can invite a partner.");
    }
}
