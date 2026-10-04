package com.couplefinance.identity.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/refresh}: the refresh token is the credential. */
@Schema(name = "RefreshRequest")
record RefreshRequest(
        @Schema(description = "The opaque refresh token received at login or at the previous refresh.")
        @NotBlank @Size(max = 256)
        String refreshToken) {

    /** Never print the credential. */
    @Override
    public String toString() {
        return "RefreshRequest[]";
    }
}
