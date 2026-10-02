package com.couplefinance.analytics.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.budget.api.BudgetSummary;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * Household analytics of one budget period (BR-ANA-01..06). Every total carries the {@code scope} (BR-SCP-03).
 *
 * @param categories         categories with a net spending of zero or more, with their share of the sum of those
 *                           categories (BR-ANA-05)
 * @param negativeCategories categories whose net spending is negative (refunds above purchases), as-is and outside
 *                           the percentage breakdown (BR-ANA-06)
 * @param members            net spending by payer (BR-ANA-04)
 * @param previousPeriod     comparison with the preceding calendar period, {@code null} when there is none
 * @param threePeriodAverage BR-ANA-03, {@code null} when no eligible period exists
 * @param budget             overall budget consumption, {@code null} when the period has no overall limit
 */
public record PeriodAnalytics(SpendingScope scope, LocalDate periodStart, LocalDate periodEnd, Money total,
        List<CategoryShare> categories, List<CategoryTotal> negativeCategories, List<MemberTotal> members,
        @Nullable PeriodComparison previousPeriod, @Nullable PeriodAverage threePeriodAverage,
        @Nullable BudgetSummary budget) {

    public PeriodAnalytics {
        categories = List.copyOf(categories);
        negativeCategories = List.copyOf(negativeCategories);
        members = List.copyOf(members);
    }

    /** A category of the percentage breakdown; {@code percentage} has one decimal. */
    public record CategoryShare(UUID categoryId, Money total, BigDecimal percentage) {}

    /** A category outside the percentage breakdown. */
    public record CategoryTotal(UUID categoryId, Money total) {}

    /** Net spending paid by one member. */
    public record MemberTotal(UUID userId, Money total) {}

    /**
     * @param difference       {@code total - previous total}
     * @param changePercentage {@code difference / previous total} in percent (BR-MON-09), {@code null} unless the
     *                         previous total is strictly positive
     */
    public record PeriodComparison(LocalDate periodStart, LocalDate periodEnd, Money total, Money difference,
            @Nullable BigDecimal changePercentage) {}

    /** @param periodsUsed how many periods (1 to 3) the average is based on */
    public record PeriodAverage(Money average, int periodsUsed) {}
}
