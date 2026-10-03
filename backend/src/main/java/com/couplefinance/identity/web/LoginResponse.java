package com.couplefinance.identity.web;

import com.couplefinance.identity.application.LoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LoginResponse")
record LoginResponse(
        @Schema(description = "Short-lived ES256 JWT, sent as a Bearer token.")
        String accessToken,

        @Schema(description = "Opaque refresh token, returned only once. Keep it in secure storage.")
        String refreshToken,

        @Schema(description = "Access-token lifetime in seconds.", example = "900")
        long expiresIn,

        @Schema(example = "Bearer")
        String tokenType) {

    static LoginResponse from(LoginResult result) {
        return new LoginResponse(result.accessToken(), result.refreshToken(), result.expiresInSeconds(), "Bearer");
    }

    @Override
    public String toString() {
        return "LoginResponse[]";
    }
}
