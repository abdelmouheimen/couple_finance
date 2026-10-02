package com.couplefinance.household.web;

import java.time.Instant;
import java.util.UUID;

import com.couplefinance.household.domain.Invitation;
import io.swagger.v3.oas.annotations.media.Schema;

/** An open invitation. Never carries the code: it is returned once, at creation. */
@Schema(name = "Invitation", requiredProperties = {"id", "createdAt", "expiresAt"})
record InvitationResponse(UUID id, Instant createdAt, Instant expiresAt) {

    static InvitationResponse from(Invitation invitation) {
        return new InvitationResponse(invitation.id(), invitation.createdAt(), invitation.expiresAt());
    }
}
