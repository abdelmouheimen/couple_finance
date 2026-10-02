package com.couplefinance.analytics.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.couplefinance.analytics.application.PeriodAnalytics.CategoryShare;
import com.couplefinance.analytics.application.PeriodAnalytics.CategoryTotal;
import com.couplefinance.analytics.application.PeriodAnalytics.MemberTotal;
import com.couplefinance.analytics.application.PeriodAnalytics.PeriodAverage;
import com.couplefinance.analytics.application.PeriodAnalytics.PeriodComparison;
import com.couplefinance.analytics.domain.AnalyticsErrorCode;
import com.couplefinance.analytics.domain.LargestRemainder;
import com.couplefinance.budget.api.BudgetSummaries;
import com.couplefinance.expense.api.SpendingGrouping;
import com.couplefinance.expense.api.SpendingQuery;
import com.couplefinance.expense.api.SpendingReport;
import com.couplefinance.expense.api.SpendingReport.CategorySpending;
import com.couplefinance.expense.api.SpendingReport.PayerSpending;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.household.api.BudgetPeriods;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.TrackingStart;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Household analytics of a budget period. Read-only: the household comes from the authenticated user, every
 * figure from the spending query (BR-SCP-01, so PERSONAL spending is structurally absent), the budget facade and
 * the period calendar. No external call is made.
 */
@Service
public class PeriodAnalyticsService {

    /** BR-ANA-03: the average spans at most this many preceding periods. */
    static final int AVERAGE_PERIODS = 3;

    private final CurrentHousehold currentHousehold;
    private final BudgetPeriods periods;
    private final TrackingStart trackingStart;
    private final SpendingQuery spending;
    private final BudgetSummaries budgets;

    PeriodAnalyticsService(CurrentHousehold currentHousehold, BudgetPeriods periods, TrackingStart trackingStart,
            SpendingQuery spending, BudgetSummaries budgets) {
        this.currentHousehold = currentHousehold;
        this.periods = periods;
        this.trackingStart = trackingStart;
        this.spending = spending;
        this.budgets = budgets;
    }

    /**
     * @throws ApplicationException {@code ANALYTICS_SCOPE_UNSUPPORTED} (400) for a scope other than HOUSEHOLD,
     *         {@code ANALYTICS_PERIOD_NOT_FOUND} (404) when {@code periodStart} starts no period of the household
     */
    @Transactional(readOnly = true)
    public PeriodAnalytics household(SpendingScope scope, LocalDate periodStart) {
        if (scope != SpendingScope.HOUSEHOLD) {
            throw new ApplicationException(AnalyticsErrorCode.ANALYTICS_SCOPE_UNSUPPORTED,
                    "Only the HOUSEHOLD scope is supported.");
        }
        HouseholdContext context = currentHousehold.currentHousehold();
        BudgetPeriod period = periods.findPeriodContaining(context, periodStart)
                .filter(p -> p.start().equals(periodStart))
                .orElseThrow(() -> new ApplicationException(AnalyticsErrorCode.ANALYTICS_PERIOD_NOT_FOUND,
                        "There is no budget period starting on this date."));

        SpendingReport report = spending.spending(context, SpendingScope.HOUSEHOLD, period.start(), period.end(),
                Set.of(SpendingGrouping.CATEGORY, SpendingGrouping.PAYER));
        Money total = report.total();

        List<CategorySpending> byCategory = report.byCategory().stream()
                .sorted(Comparator.<CategorySpending, Long>comparing(c -> c.total().minorUnits()).reversed()
                        .thenComparing(CategorySpending::categoryId))
                .toList();
        // BR-ANA-06: a negative net category is shown apart; BR-ANA-05: the others share 100.0 exactly.
        List<CategorySpending> nonNegative = byCategory.stream().filter(c -> !c.total().isNegative()).toList();
        List<BigDecimal> shares = LargestRemainder
                .percentages(nonNegative.stream().map(c -> c.total().minorUnits()).toList());
        List<CategoryShare> categories = new ArrayList<>();
        for (int i = 0; i < nonNegative.size(); i++) {
            categories.add(new CategoryShare(nonNegative.get(i).categoryId(), nonNegative.get(i).total(),
                    shares.get(i)));
        }
        List<CategoryTotal> negative = byCategory.stream().filter(c -> c.total().isNegative())
                .map(c -> new CategoryTotal(c.categoryId(), c.total())).toList();
        // BR-ANA-04: by payer, as reported by the spending query.
        List<MemberTotal> members = report.byPayer().stream()
                .sorted(Comparator.<PayerSpending, Long>comparing(p -> p.total().minorUnits()).reversed()
                        .thenComparing(PayerSpending::userId))
                .map(p -> new MemberTotal(p.userId(), p.total())).toList();

        List<BudgetPeriod> preceding = precedingPeriods(context, period);
        return new PeriodAnalytics(SpendingScope.HOUSEHOLD, period.start(), period.end(), total, categories,
                negative, members, comparison(context, total, preceding), average(context, total, preceding),
                budgets.overallSummary(context, period.start()).orElse(null));
    }

    /** Up to {@link #AVERAGE_PERIODS} calendar periods immediately before {@code period}, most recent first. */
    private List<BudgetPeriod> precedingPeriods(HouseholdContext context, BudgetPeriod period) {
        List<BudgetPeriod> result = new ArrayList<>();
        LocalDate cursor = period.start();
        while (result.size() < AVERAGE_PERIODS) {
            var previous = periods.findPeriodContaining(context, cursor.minusDays(1));
            if (previous.isEmpty()) {
                break;
            }
            result.add(previous.get());
            cursor = previous.get().start();
        }
        return result;
    }

    private @Nullable PeriodComparison comparison(HouseholdContext context, Money total,
            List<BudgetPeriod> preceding) {
        if (preceding.isEmpty()) {
            return null;
        }
        BudgetPeriod previous = preceding.get(0);
        Money previousTotal = totalOf(context, previous);
        Money difference = total.subtract(previousTotal);
        BigDecimal change = previousTotal.isPositive() ? difference.percentageOf(previousTotal) : null;
        return new PeriodComparison(previous.start(), previous.end(), previousTotal, difference, change);
    }

    /**
     * BR-ANA-03: the (up to 3) periods preceding the reference one that start on or after the tracking start;
     * minimum 1. The mean of their minor-unit totals is rounded once, HALF_EVEN, at the end.
     */
    private @Nullable PeriodAverage average(HouseholdContext context, Money total, List<BudgetPeriod> preceding) {
        LocalDate tracking = trackingStart.of(context.householdId());
        List<BudgetPeriod> eligible = preceding.stream().filter(p -> !p.start().isBefore(tracking)).toList();
        if (eligible.isEmpty()) {
            return null;
        }
        long sum = 0;
        for (BudgetPeriod p : eligible) {
            sum = Math.addExact(sum, totalOf(context, p).minorUnits());
        }
        long average = BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(eligible.size()), 0, RoundingMode.HALF_EVEN).longValueExact();
        return new PeriodAverage(Money.ofMinor(average, total.currency(), total.amount().scale()),
                eligible.size());
    }

    private Money totalOf(HouseholdContext context, BudgetPeriod period) {
        return spending.spending(context, SpendingScope.HOUSEHOLD, period.start(), period.end(), Set.of()).total();
    }
}
