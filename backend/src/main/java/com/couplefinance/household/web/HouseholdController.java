package com.couplefinance.household.web;

import com.couplefinance.household.application.CreateHouseholdService;
import com.couplefinance.household.application.GetCurrentHousehold;
import com.couplefinance.identity.api.CurrentUser;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households")
@Tag(name = "Households")
class HouseholdController {

    private final CurrentUser currentUser;
    private final CreateHouseholdService createHousehold;
    private final GetCurrentHousehold getCurrentHousehold;

    HouseholdController(CurrentUser currentUser, CreateHouseholdService createHousehold,
                        GetCurrentHousehold getCurrentHousehold) {
        this.currentUser = currentUser;
        this.createHousehold = createHousehold;
        this.getCurrentHousehold = getCurrentHousehold;
    }

    @GetMapping("/me")
    @Operation(operationId = "getCurrentHousehold", summary = "Get the current household",
            description = "Returns the household of the authenticated user, derived server-side. A former member "
                    + "of a dissolved household reads it (status DISSOLVED) until the archive access expires.")
    @ApiResponse(responseCode = "200", description = "The caller's household")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND: no household and no archive access",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    HouseholdResponse getCurrent() {
        return HouseholdResponse.from(getCurrentHousehold.getCurrentHousehold());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createHousehold", summary = "Create a household",
            description = "Creates a household whose creator — the authenticated user — becomes its first active "
                    + "member. A user can belong to only one active household.")
    @ApiResponse(responseCode = "201", description = "Household created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST, UNSUPPORTED_CURRENCY "
            + "or INVALID_TIMEZONE", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "ALREADY_IN_HOUSEHOLD",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    HouseholdResponse create(@Valid @RequestBody CreateHouseholdRequest request) {
        return HouseholdResponse.from(createHousehold.create(currentUser.requireActiveUser(), request.toCommand()));
    }
}
