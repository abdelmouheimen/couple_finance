package com.couplefinance.identity.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/auth/resend-verification}. */
@Schema(name = "ResendVerificationRequest")
record ResendVerificationRequest(
        @Schema(example = "alex@example.com")
        @NotBlank @Email @Size(max = 254)
        String email) {
}
