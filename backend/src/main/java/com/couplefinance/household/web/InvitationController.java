package com.couplefinance.household.web;

import java.util.List;
import java.util.UUID;

import com.couplefinance.household.application.InvitationService;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/me/invitations")
@Tag(name = "Households")
class InvitationController {

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createInvitation", summary = "Invite the partner",
            description = "Creates a single-use invitation valid 7 days and returns its code exactly once. "
                    + "Only the hash of the code is stored. A pending invitation of the household is replaced "
                    + "(revoked). Rate-limited per user and per IP.")
    @ApiResponse(responseCode = "201", description = "Invitation created; the code is shown only now")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY (dissolved)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "INVITATION_NOT_ALLOWED: the household does not have "
            + "exactly one active member", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    CreatedInvitationResponse create() {
        return CreatedInvitationResponse.from(invitations.create());
    }

    @GetMapping
    @Operation(operationId = "listInvitations", summary = "List the caller's open invitations",
            description = "Open (active, unexpired) invitations created by the caller. Never includes the code.")
    @ApiResponse(responseCode = "200", description = "Open invitations")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    List<InvitationResponse> list() {
        return invitations.listActive().stream().map(InvitationResponse::from).toList();
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "revokeInvitation", summary = "Revoke an invitation",
            description = "Revokes an invitation created by the caller; it can never be redeemed afterwards. "
                    + "Idempotent for an already revoked invitation.")
    @ApiResponse(responseCode = "204", description = "Revoked")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or INVITATION_NOT_FOUND (unknown, "
            + "other household, or created by someone else)", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "INVITATION_NOT_REVOCABLE: already redeemed",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<Void> revoke(@PathVariable UUID id) {
        invitations.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
