package com.couplefinance.household.web;

import java.time.Instant;
import java.util.UUID;

import com.couplefinance.household.application.InvitationService.CreatedInvitation;
import io.swagger.v3.oas.annotations.media.Schema;

/** The only response that carries the invitation code (BR-HH-04): it cannot be retrieved afterwards. */
@Schema(name = "CreatedInvitation", requiredProperties = {"id", "code", "expiresAt"})
record CreatedInvitationResponse(
        UUID id,
        @Schema(description = "Single-use secret, shown once. Share it only with the partner.") String code,
        Instant expiresAt) {

    static CreatedInvitationResponse from(CreatedInvitation created) {
        return new CreatedInvitationResponse(created.invitation().id(), created.code().value(),
                created.invitation().expiresAt());
    }

    @Override
    public String toString() {
        return "CreatedInvitationResponse[id=" + id + ", code=redacted, expiresAt=" + expiresAt + "]";
    }
}
