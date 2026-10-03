package com.couplefinance.identity.web;

import com.couplefinance.identity.application.GetCurrentUserProfile;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Identity")
class CurrentUserController {

    private final GetCurrentUserProfile getCurrentUserProfile;

    CurrentUserController(GetCurrentUserProfile getCurrentUserProfile) {
        this.getCurrentUserProfile = getCurrentUserProfile;
    }

    @GetMapping
    @Operation(operationId = "getCurrentUser", summary = "Get the authenticated user",
            description = "Returns the identity of the authenticated user, derived from the access token only.")
    @ApiResponse(responseCode = "200", description = "The caller's identity")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    CurrentUserResponse me() {
        return CurrentUserResponse.from(getCurrentUserProfile.get());
    }
}
