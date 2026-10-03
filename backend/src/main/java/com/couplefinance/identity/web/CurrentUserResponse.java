package com.couplefinance.identity.web;

import java.util.UUID;

import com.couplefinance.identity.application.CurrentUserProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "CurrentUserResponse")
record CurrentUserResponse(
        UUID id,
        String email,
        @Nullable String displayName,
        @Schema(description = "BCP 47 language tag.", example = "fr-FR")
        String locale,
        @Schema(description = "Account status; only ACTIVE accounts reach protected endpoints.",
                allowableValues = {"PENDING_VERIFICATION", "ACTIVE"})
        String status) {

    static CurrentUserResponse from(CurrentUserProfile profile) {
        return new CurrentUserResponse(profile.id(), profile.email(), profile.displayName(), profile.locale(),
                profile.status().name());
    }
}
