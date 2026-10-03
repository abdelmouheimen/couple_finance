package com.couplefinance.analytics.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.analytics.application.PeriodAnalytics;
import com.couplefinance.budget.api.BudgetSummary;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Analytics of a budget period as returned by the API. Every figure carries the scope (BR-SCP-03). */
@Schema(name = "PeriodAnalytics", requiredProperties = {"scope", "periodStart", "periodEnd", "total", "categories",
        "negativeCategories", "members"})
record PeriodAnalyticsResponse(
        @Schema(description = "Scope of every figure (BR-SCP-03); HOUSEHOLD never includes PERSONAL spending "
                + "(BR-SCP-01), PERSONAL is the caller's own only (BR-SCP-02).") SpendingScope scope,
        @Schema(description = "First day of the period (inclusive).") LocalDate periodStart,
        @Schema(description = "End of the period (exclusive).") LocalDate periodEnd,
        @Schema(implementation = MoneySchema.class, description = "Net spending of the period in the scope: expenses "
                + "minus refunds, deleted expenses and transfers excluded.") Money total,
        @Schema(description = "Categories with a net spending of zero or more, largest first, with their share of "
                + "the sum of these categories; the percentages sum to exactly 100.0 (BR-ANA-05) unless all are "
                + "zero.") List<CategoryShareResponse> categories,
        @Schema(description = "Categories whose net spending is negative (refunds above purchases), shown as-is "
                + "and excluded from the percentage breakdown (BR-ANA-06).")
        List<CategoryTotalResponse> negativeCategories,
        @Schema(description = "Net spending by payer, never by creator (BR-ANA-04); empty for the PERSONAL scope.")
        List<MemberTotalResponse> members,
        @Schema(description = "Comparison with the preceding calendar period; absent when there is none.")
        @Nullable PreviousPeriodResponse previousPeriod,
        @Schema(description = "BR-ANA-03: average of the up to 3 complete periods preceding this one that start on "
                + "or after the household tracking start; absent when none is eligible.")
        @Nullable ThreePeriodAverageResponse threePeriodAverage,
        @Schema(description = "Overall budget of the period; absent when the period has no overall limit.")
        @Nullable BudgetSummaryResponse budget) {

    static PeriodAnalyticsResponse from(PeriodAnalytics a) {
        PeriodAnalytics.PeriodComparison previous = a.previousPeriod();
        return new PeriodAnalyticsResponse(a.scope(), a.periodStart(), a.periodEnd(), a.total(),
                a.categories().stream().map(c -> new CategoryShareResponse(c.categoryId(), c.total(),
                        c.percentage().toPlainString())).toList(),
                a.negativeCategories().stream().map(c -> new CategoryTotalResponse(c.categoryId(), c.total()))
                        .toList(),
                a.members().stream().map(m -> new MemberTotalResponse(m.userId(), m.total())).toList(),
                previous == null ? null : new PreviousPeriodResponse(previous.periodStart(), previous.periodEnd(),
                        previous.total(), previous.difference(),
                        previous.changePercentage() == null ? null : previous.changePercentage().toPlainString()),
                a.threePeriodAverage() == null ? null : new ThreePeriodAverageResponse(
                        a.threePeriodAverage().average(), a.threePeriodAverage().periodsUsed()),
                a.budget() == null ? null : BudgetSummaryResponse.from(a.budget()));
    }

    @Schema(name = "AnalyticsCategoryShare")
    record CategoryShareResponse(UUID categoryId, @Schema(implementation = MoneySchema.class) Money total,
            @Schema(description = "Share of the breakdown in percent, one decimal, largest remainder (BR-ANA-05).",
                    example = "42.5") String percentage) {}

    @Schema(name = "AnalyticsCategoryTotal")
    record CategoryTotalResponse(UUID categoryId, @Schema(implementation = MoneySchema.class) Money total) {}

    @Schema(name = "AnalyticsMemberTotal")
    record MemberTotalResponse(UUID userId, @Schema(implementation = MoneySchema.class) Money total) {}

    @Schema(name = "AnalyticsPreviousPeriod")
    record PreviousPeriodResponse(LocalDate periodStart, LocalDate periodEnd,
            @Schema(implementation = MoneySchema.class) Money total,
            @Schema(implementation = MoneySchema.class, description = "This period's total minus the previous "
                    + "one's.") Money difference,
            @Schema(description = "difference / previous total in percent, one decimal, HALF_EVEN (BR-MON-09); "
                    + "absent unless the previous total is strictly positive.", example = "-3.2")
            @Nullable String changePercentage) {}

    @Schema(name = "AnalyticsThreePeriodAverage")
    record ThreePeriodAverageResponse(@Schema(implementation = MoneySchema.class) Money average,
            @Schema(description = "Number of periods averaged, 1 to 3.") int periodsUsed) {}

    @Schema(name = "AnalyticsBudgetSummary")
    record BudgetSummaryResponse(@Schema(implementation = MoneySchema.class) Money limit,
            @Schema(implementation = MoneySchema.class) Money consumed,
            @Schema(implementation = MoneySchema.class, description = "limit - consumed; negative when exceeded.")
            Money remaining,
            @Schema(description = "consumed / limit in percent, one decimal (BR-MON-09).", example = "82.5")
            String percentage,
            @Schema(description = "ON_TRACK below 80.0%, WARNING from 80.0% to 100.0% inclusive, EXCEEDED above.")
            BudgetSummary.Status status) {

        static BudgetSummaryResponse from(BudgetSummary s) {
            return new BudgetSummaryResponse(s.limit(), s.consumed(), s.remaining(), s.percentage().toPlainString(),
                    s.status());
        }
    }
}
