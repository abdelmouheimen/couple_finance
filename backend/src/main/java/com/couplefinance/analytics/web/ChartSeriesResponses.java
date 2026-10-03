package com.couplefinance.analytics.web;

import java.time.LocalDate;
import java.util.List;

import com.couplefinance.analytics.application.ChartSeries;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Response DTOs of the chart series. Every series carries its scope (BR-SCP-03). */
final class ChartSeriesResponses {

    private ChartSeriesResponses() {
    }

    @Schema(name = "DailyCumulativeSeries",
            requiredProperties = {"scope", "periodStart", "periodEnd", "points"})
    record DailyCumulativeResponse(
            @Schema(description = "Scope of every figure (BR-SCP-03).") SpendingScope scope,
            @Schema(description = "First day of the period (inclusive).") LocalDate periodStart,
            @Schema(description = "End of the period (exclusive).") LocalDate periodEnd,
            @Schema(description = "One point per day from the period start to the last day of the period, or to "
                    + "today in the household timezone for the current period; days without spending repeat the "
                    + "previous cumulative; future days are absent (empty for a future period).")
            List<DayPointResponse> points,
            @Schema(implementation = MoneySchema.class, description = "Overall budget limit for reference; absent "
                    + "without an overall limit and for the PERSONAL scope (BR-BUD-02).") @Nullable Money limit) {

        static DailyCumulativeResponse from(ChartSeries.DailyCumulative s) {
            return new DailyCumulativeResponse(s.scope(), s.periodStart(), s.periodEnd(),
                    s.points().stream().map(p -> new DayPointResponse(p.date(), p.cumulative())).toList(),
                    s.limit());
        }
    }

    @Schema(name = "DailyCumulativePoint")
    record DayPointResponse(LocalDate date,
            @Schema(implementation = MoneySchema.class, description = "Net spending from the period start through "
                    + "this day inclusive.") Money cumulative) {}

    @Schema(name = "TrendSeries", requiredProperties = {"scope", "periods"})
    record TrendResponse(
            @Schema(description = "Scope of every figure (BR-SCP-03).") SpendingScope scope,
            @Schema(description = "Net totals of the last 6 periods up to and including the current one, oldest "
                    + "first; fewer when the household has fewer periods since its tracking start.")
            List<PeriodTotalResponse> periods) {

        static TrendResponse from(ChartSeries.Trend t) {
            return new TrendResponse(t.scope(), t.periods().stream().map(p -> new PeriodTotalResponse(
                    p.periodStart(), p.periodEnd(), p.total(), p.current())).toList());
        }
    }

    @Schema(name = "TrendPeriodTotal")
    record PeriodTotalResponse(LocalDate periodStart, LocalDate periodEnd,
            @Schema(implementation = MoneySchema.class) Money total,
            @Schema(description = "True for the period containing today, still in progress.") boolean current) {}
}
