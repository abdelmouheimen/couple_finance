package com.couplefinance.analytics.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.couplefinance.analytics.application.ChartSeries.DailyCumulative;
import com.couplefinance.analytics.application.ChartSeries.DayPoint;
import com.couplefinance.analytics.application.ChartSeries.PeriodTotal;
import com.couplefinance.analytics.application.ChartSeries.Trend;
import com.couplefinance.analytics.domain.AnalyticsErrorCode;
import com.couplefinance.budget.api.BudgetSummaries;
import com.couplefinance.expense.api.SpendingGrouping;
import com.couplefinance.expense.api.SpendingQuery;
import com.couplefinance.expense.api.SpendingReport;
import com.couplefinance.expense.api.SpendingReport.DaySpending;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.household.api.BudgetPeriods;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.TrackingStart;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chart series for the mobile app (BR-ANA-01/02). Read-only; every figure comes from the spending query in minor
 * units (integer arithmetic) for the requested scope only, so a partner's PERSONAL spending is unreachable
 * (BR-SCP-01/02). No external call is made.
 */
@Service
public class ChartSeriesService {

    /** Number of periods of the trend series. */
    static final int TREND_PERIODS = 6;

    private final CurrentHousehold currentHousehold;
    private final BudgetPeriods periods;
    private final TrackingStart trackingStart;
    private final SpendingQuery spending;
    private final BudgetSummaries budgets;
    private final HouseholdLedgerRules ledgerRules;
    private final Clock clock;

    ChartSeriesService(CurrentHousehold currentHousehold, BudgetPeriods periods, TrackingStart trackingStart,
            SpendingQuery spending, BudgetSummaries budgets, HouseholdLedgerRules ledgerRules, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.periods = periods;
        this.trackingStart = trackingStart;
        this.spending = spending;
        this.budgets = budgets;
        this.ledgerRules = ledgerRules;
        this.clock = clock;
    }

    /**
     * Cumulative spending per day of the period starting on {@code periodStart}. Days without spending are present
     * with an unchanged cumulative; days after today (household timezone) are not part of the series.
     *
     * @throws ApplicationException {@code ANALYTICS_PERIOD_NOT_FOUND} (404) when no period starts on that date
     */
    @Transactional(readOnly = true)
    public DailyCumulative dailyCumulative(SpendingScope scope, LocalDate periodStart) {
        HouseholdContext context = currentHousehold.currentHousehold();
        BudgetPeriod period = periods.findPeriodContaining(context, periodStart)
                .filter(p -> p.start().equals(periodStart))
                .orElseThrow(() -> new ApplicationException(AnalyticsErrorCode.ANALYTICS_PERIOD_NOT_FOUND,
                        "There is no budget period starting on this date."));
        SpendingReport report = spending.spending(context, scope, period.start(), period.end(),
                Set.of(SpendingGrouping.DAY));
        List<DayPoint> points = series(period, today(context), report);
        Money limit = scope == SpendingScope.PERSONAL ? null
                : budgets.overallSummary(context, period.start()).map(s -> s.limit()).orElse(null);
        return new DailyCumulative(scope, period.start(), period.end(), points, limit);
    }

    /** Pure construction of the points (BR-ANA-02: integer minor units), separated for unit testing. */
    static List<DayPoint> series(BudgetPeriod period, LocalDate today, SpendingReport report) {
        var currency = report.total().currency();
        int decimals = report.total().amount().scale();
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (DaySpending d : report.byDay()) {
            byDay.merge(d.date(), d.total().minorUnits(), Math::addExact);
        }
        List<DayPoint> points = new ArrayList<>();
        long cumulative = 0;
        for (LocalDate day = period.start(); day.isBefore(period.end()) && !day.isAfter(today);
                day = day.plusDays(1)) {
            cumulative = Math.addExact(cumulative, byDay.getOrDefault(day, 0L));
            points.add(new DayPoint(day, Money.ofMinor(cumulative, currency, decimals)));
        }
        return points;
    }

    /**
     * Net totals of the last {@value #TREND_PERIODS} periods up to and including the one containing today, oldest
     * first, without any period that ended before the tracking start (BR-ANA-03: the period containing it is kept,
     * as it holds the first recorded spending); fewer when fewer exist.
     */
    @Transactional(readOnly = true)
    public Trend trend(SpendingScope scope) {
        HouseholdContext context = currentHousehold.currentHousehold();
        LocalDate today = today(context);
        LocalDate tracking = trackingStart.of(context.householdId());
        List<BudgetPeriod> selected = new ArrayList<>();
        var cursor = periods.findPeriodContaining(context, today);
        while (cursor.isPresent() && selected.size() < TREND_PERIODS && cursor.get().end().isAfter(tracking)) {
            selected.add(0, cursor.get());
            cursor = periods.findPeriodContaining(context, cursor.get().start().minusDays(1));
        }
        List<PeriodTotal> totals = new ArrayList<>();
        for (BudgetPeriod p : selected) {
            Money total = spending.spending(context, scope, p.start(), p.end(), Set.of()).total();
            totals.add(new PeriodTotal(p.start(), p.end(), total, p.contains(today)));
        }
        return new Trend(scope, totals);
    }

    private LocalDate today(HouseholdContext context) {
        return LocalDate.now(clock.withZone(ledgerRules.profile(context.householdId()).timezone()));
    }
}
