package com.couplefinance.analytics.web;

import java.time.LocalDate;

import com.couplefinance.analytics.application.ChartSeriesService;
import com.couplefinance.analytics.web.ChartSeriesResponses.DailyCumulativeResponse;
import com.couplefinance.analytics.web.ChartSeriesResponses.TrendResponse;
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

/** Chart series of the caller's household, computed by the backend (BR-ANA-01/02, BR-SCP-03). */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics")
class ChartSeriesController {

    private final ChartSeriesService series;

    ChartSeriesController(ChartSeriesService series) {
        this.series = series;
    }

    @GetMapping("/periods/{periodStart}/daily-cumulative")
    @Operation(operationId = "getDailyCumulativeSeries", summary = "Cumulative daily spending of a budget period",
            description = "For each day of the period (up to today in the household timezone for the current "
                    + "period), the cumulative net spending of the requested scope, and the overall budget limit "
                    + "for reference when one exists (HOUSEHOLD only). HOUSEHOLD never includes a PERSONAL expense "
                    + "(BR-SCP-01); PERSONAL is the caller's own only (BR-SCP-02). Computed in integer minor units.")
    @ApiResponse(responseCode = "200", description = "The series")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (malformed date, missing or unknown scope)",
            content = @Content(mediaType = "application/problem+json",
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
    DailyCumulativeResponse dailyCumulative(
            @Parameter(description = "First day of the budget period (YYYY-MM-DD).")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @Parameter(description = "HOUSEHOLD, or PERSONAL for the caller's own personal spending.",
                    required = true) @RequestParam SpendingScope scope) {
        return DailyCumulativeResponse.from(series.dailyCumulative(scope, periodStart));
    }

    @GetMapping("/trend")
    @Operation(operationId = "getTrendSeries", summary = "Net totals of the last 6 budget periods",
            description = "Net spending totals of the last 6 periods up to and including the current one (oldest "
                    + "first), none that ended before the household tracking start; fewer when the household has "
                    + "fewer. Same scope rules as the daily series.")
    @ApiResponse(responseCode = "200", description = "The series")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (missing or unknown scope)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    TrendResponse trend(@Parameter(description = "HOUSEHOLD, or PERSONAL for the caller's own personal spending.",
            required = true) @RequestParam SpendingScope scope) {
        return TrendResponse.from(series.trend(scope));
    }
}
