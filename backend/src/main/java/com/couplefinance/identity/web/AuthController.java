package com.couplefinance.identity.web;

import com.couplefinance.identity.application.LoginService;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
class AuthController {

    private final LoginService loginService;

    AuthController(LoginService loginService) {
        this.loginService = loginService;
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
}
