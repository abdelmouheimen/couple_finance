package com.couplefinance.identity.web;

import com.couplefinance.identity.application.LoginService;
import com.couplefinance.identity.application.LogoutService;
import com.couplefinance.identity.application.RefreshService;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController {

    private final LoginService loginService;
    private final RefreshService refreshService;
    private final LogoutService logoutService;

    AuthController(LoginService loginService, RefreshService refreshService, LogoutService logoutService) {
        this.loginService = loginService;
        this.refreshService = refreshService;
        this.logoutService = logoutService;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(operationId = "login", summary = "Log in with email and password",
            description = "Public. Creates a session and returns a short-lived access token and an opaque refresh "
                    + "token (returned once). Wrong password, unknown email and deleted account are "
                    + "indistinguishable (INVALID_CREDENTIALS). An account whose email is not verified receives "
                    + "tokens; protected endpoints then answer EMAIL_NOT_VERIFIED.")
    @ApiResponse(responseCode = "200", description = "Tokens issued")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or MALFORMED_REQUEST",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "413", description = "PAYLOAD_TOO_LARGE",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED, with a Retry-After header",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return LoginResponse.from(loginService.login(request.toCommand()));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(operationId = "refresh", summary = "Rotate the refresh token",
            description = "Public: the refresh token is the credential. Returns a new access token for the same "
                    + "session and a new refresh token (30 days, sliding); the presented token is rotated. A token "
                    + "rotated less than 30 seconds ago (parallel refresh) rotates the chain forward again so only "
                    + "the newest token stays valid; a rotated token presented later revokes the session. Unknown, "
                    + "expired, revoked and replayed tokens are indistinguishable (INVALID_REFRESH_TOKEN).")
    @ApiResponse(responseCode = "200", description = "Tokens issued")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or MALFORMED_REQUEST",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_REFRESH_TOKEN",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "413", description = "PAYLOAD_TOO_LARGE",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED, with a Retry-After header",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return LoginResponse.from(refreshService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "logout", summary = "Log out of the current session",
            description = "Revokes the session of the access token (reason LOGOUT) and its refresh tokens. "
                    + "Idempotent. The access token itself stays valid until it expires (at most 15 minutes).")
    @ApiResponse(responseCode = "204", description = "Session revoked")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    void logout() {
        logoutService.logout();
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "logoutAll", summary = "Log out of every session",
            description = "Revokes all sessions of the authenticated user (reason LOGOUT_ALL) and their refresh "
                    + "tokens.")
    @ApiResponse(responseCode = "204", description = "Sessions revoked")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    void logoutAll() {
        logoutService.logoutAll();
    }
}
