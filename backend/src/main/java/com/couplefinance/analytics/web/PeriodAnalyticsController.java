package com.couplefinance.analytics.web;

import java.time.LocalDate;

import com.couplefinance.analytics.application.PeriodAnalyticsService;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Analytics of a budget period of the caller's household (BR-ANA-01..06). */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics")
class PeriodAnalyticsController {

    private final PeriodAnalyticsService analytics;

    PeriodAnalyticsController(PeriodAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/periods/{periodStart}")
    @Operation(operationId = "getPeriodAnalytics", summary = "Household analytics of a budget period",
            description = "Total spending of the period, breakdown by category (percentages summing to 100.0, "
                    + "negative net categories shown apart) and by payer, comparison with the previous period, "
                    + "3-period average and overall budget consumption. Computed on read by the backend from "
                    + "household spending only (BR-SCP-01): no PERSONAL expense contributes to any figure. Only "
                    + "scope=HOUSEHOLD is supported for now. A date that is not the start of a period of the "
                    + "household calendar is a 404. Readable by archive readers of a dissolved household.")
    @ApiResponse(responseCode = "200", description = "The analytics of the period")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (malformed date, unknown scope) or "
            + "ANALYTICS_SCOPE_UNSUPPORTED (PERSONAL)", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or ANALYTICS_PERIOD_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    PeriodAnalyticsResponse get(
            @Parameter(description = "First day of the budget period (YYYY-MM-DD).")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @Parameter(description = "HOUSEHOLD is the only supported scope.", required = true)
            @RequestParam SpendingScope scope) {
        return PeriodAnalyticsResponse.from(analytics.household(scope, periodStart));
    }
}
