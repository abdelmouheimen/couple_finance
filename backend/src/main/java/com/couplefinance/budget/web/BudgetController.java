package com.couplefinance.budget.web;

import java.time.LocalDate;

import com.couplefinance.budget.application.BudgetService;
import com.couplefinance.budget.application.SetBudgetResult;
import com.couplefinance.shared.concurrency.VersionETag;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Budgets of the caller's household, one resource per budget period (BR-BUD-01, BR-BUD-02). */
@RestController
@RequestMapping("/api/v1/budgets")
@Tag(name = "Budgets")
class BudgetController {

    private final BudgetService budgets;

    BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @GetMapping("/{periodStart}")
    @Operation(operationId = "getBudget", summary = "Read the budget of a period",
            description = "The budget of the household's period starting on periodStart. The ETag header carries "
                    + "the version. A date that is not the start of a period of the household calendar, and a "
                    + "period without budget, are a 404. Readable by archive readers of a dissolved household.")
    @ApiResponse(responseCode = "200", description = "The budget")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (malformed date)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND, BUDGET_PERIOD_NOT_FOUND or "
            + "BUDGET_NOT_FOUND", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<BudgetResponse> get(
            @Parameter(description = "First day of the budget period (YYYY-MM-DD).")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        var budget = budgets.get(periodStart);
        return ResponseEntity.ok().eTag(VersionETag.render(budget.version())).body(BudgetResponse.from(budget));
    }

    @PutMapping("/{periodStart}")
    @Operation(operationId = "setBudget", summary = "Create or update the budget of a period",
            description = "Sets the overall limit of the period starting on periodStart: creates the budget "
                    + "(201, no If-Match) or updates it (200). Updating requires If-Match with the version (ETag) "
                    + "last read: missing is a 428, stale a 412; If-Match on a period without budget is a 412, as "
                    + "is losing a creation race. At most one budget per period. A budget needs at least one "
                    + "limit: omitting overallLimit is a 400 and nothing is stored. Both active members may write; "
                    + "past periods can be edited. The change is audited and emits BudgetCreated or BudgetUpdated; "
                    + "the new ETag is returned. Rejected on a dissolved household.")
    @ApiResponse(responseCode = "200", description = "Budget updated (or unchanged)")
    @ApiResponse(responseCode = "201", description = "Budget created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST, IF_MATCH_INVALID, "
            + "BUDGET_LIMIT_REQUIRED, INVALID_AMOUNT_FORMAT, TOO_MANY_DECIMALS, AMOUNT_NOT_POSITIVE, "
            + "AMOUNT_EXCEEDS_MAXIMUM or CURRENCY_MISMATCH",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or BUDGET_PERIOD_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "412", description = "VERSION_CONFLICT (stale If-Match, or the budget was "
            + "created concurrently)", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "428", description = "IF_MATCH_REQUIRED (the budget exists)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<BudgetResponse> set(
            @Parameter(description = "First day of the budget period (YYYY-MM-DD).")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @Parameter(description = "Version of the budget last read, as sent in its ETag.")
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody SetBudgetRequest request) {
        SetBudgetResult result = budgets.set(request.toCommand(periodStart, ifMatch));
        var budget = result.budget();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(VersionETag.render(budget.version())).body(BudgetResponse.from(budget));
    }
}
