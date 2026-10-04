package com.couplefinance.identity.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/verify-email}. */
@Schema(name = "VerifyEmailRequest")
record VerifyEmailRequest(
        @Schema(description = "The opaque token received by email.")
        @NotBlank @Size(max = 128)
        String token) {

    /** Never print the token. */
    @Override
    public String toString() {
        return "VerifyEmailRequest[]";
    }
}
