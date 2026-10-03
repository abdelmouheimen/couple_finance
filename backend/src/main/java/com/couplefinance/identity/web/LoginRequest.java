package com.couplefinance.identity.web;

import com.couplefinance.identity.application.LoginCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/auth/login}. The password length is bounded to avoid hashing denial of service
 * (the minimum length is enforced at registration, not here).
 */
@Schema(name = "LoginRequest")
record LoginRequest(
        @Schema(example = "alex@example.com")
        @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(description = "The account password.", format = "password")
        @NotBlank @Size(max = LoginRequest.PASSWORD_MAX_LENGTH)
        String password,

        @Schema(description = "Free label of the device, shown in the session list.", example = "Alex's phone",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(min = 1, max = 80)
        @Pattern(regexp = "[^\\p{Cntrl}]*\\S[^\\p{Cntrl}]*", message = "must not be blank or contain control characters")
        String deviceLabel) {

    static final int PASSWORD_MAX_LENGTH = 128;

    LoginCommand toCommand() {
        return new LoginCommand(email, password, deviceLabel);
    }

    /** Never print the credentials. */
    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
