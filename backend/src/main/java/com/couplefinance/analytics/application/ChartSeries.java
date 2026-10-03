package com.couplefinance.analytics.application;

import java.time.LocalDate;
import java.util.List;

import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/** Chart series computed by the backend (BR-ANA-01/02); every series carries its scope (BR-SCP-03). */
public final class ChartSeries {

    private ChartSeries() {
    }

    /**
     * Cumulative net spending day by day over a period.
     *
     * @param points one point per day from {@code periodStart} to the last day of the period, or to today (in the
     *               household timezone) for the current period; future days are never invented
     * @param limit  the overall budget limit for reference, {@code null} without overall limit and for PERSONAL
     */
    public record DailyCumulative(SpendingScope scope, LocalDate periodStart, LocalDate periodEnd,
            List<DayPoint> points, @Nullable Money limit) {

        public DailyCumulative {
            points = List.copyOf(points);
        }
    }

    /** @param cumulative net spending from the period start through {@code date} inclusive */
    public record DayPoint(LocalDate date, Money cumulative) {}

    /**
     * Net totals of the last periods, oldest first: at most six, none starting before the tracking start.
     */
    public record Trend(SpendingScope scope, List<PeriodTotal> periods) {

        public Trend {
            periods = List.copyOf(periods);
        }
    }

    /** @param current whether the period contains today, hence is still in progress */
    public record PeriodTotal(LocalDate periodStart, LocalDate periodEnd, Money total, boolean current) {}
}
