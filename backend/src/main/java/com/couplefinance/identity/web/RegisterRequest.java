package com.couplefinance.identity.web;

import com.couplefinance.identity.application.RegisterCommand;
import com.couplefinance.identity.domain.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST /api/v1/auth/register}. Status and verification state are never client-supplied. */
@Schema(name = "RegisterRequest")
record RegisterRequest(
        @Schema(example = "alex@example.com")
        @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(description = "Between 10 and 128 characters.", format = "password",
                minLength = PasswordPolicy.MIN_LENGTH, maxLength = PasswordPolicy.MAX_LENGTH)
        @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
        String password,

        @Schema(example = "Alex")
        @NotBlank @Size(min = 1, max = 60)
        @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
        String displayName,

        @Schema(description = "BCP 47 language tag, the account preference; defaults to fr-FR.", example = "fr-FR",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(min = 2, max = 35)
        @Pattern(regexp = "[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*", message = "must be a BCP 47 language tag")
        String locale) {

    RegisterCommand toCommand() {
        return new RegisterCommand(email, password, displayName, locale);
    }

    /** Never print the credentials. */
    @Override
    public String toString() {
        return "RegisterRequest[]";
    }
}
