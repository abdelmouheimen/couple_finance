package com.couplefinance.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import com.couplefinance.analytics.application.ChartSeries.DayPoint;
import com.couplefinance.expense.api.SpendingReport;
import com.couplefinance.expense.api.SpendingReport.DaySpending;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;

/** Construction of the daily cumulative series (BR-ANA-02, BR-SCP-03). */
class ChartSeriesServiceTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final BudgetPeriod PERIOD = new BudgetPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

    private static Money eur(long minor) {
        return Money.ofMinor(minor, EUR, 2);
    }

    private static SpendingReport report(long total, DaySpending... days) {
        return new SpendingReport(SpendingScope.HOUSEHOLD, PERIOD.start(), PERIOD.end(), eur(total), List.of(),
                List.of(), List.of(days));
    }

    @Test
    void BR_ANA_02_a_past_period_has_one_point_per_day_with_zero_days_repeating_the_cumulative() {
        SpendingReport report = report(1_500, new DaySpending(LocalDate.of(2026, 3, 2), eur(1_000)),
                new DaySpending(LocalDate.of(2026, 3, 5), eur(500)));

        List<DayPoint> points = ChartSeriesService.series(PERIOD, LocalDate.of(2026, 6, 1), report);

        assertThat(points).hasSize(31);
        assertThat(points.get(0).date()).isEqualTo(PERIOD.start());
        assertThat(points.get(0).cumulative()).isEqualTo(eur(0));
        assertThat(points.get(1).cumulative()).isEqualTo(eur(1_000));
        assertThat(points.get(2).cumulative()).isEqualTo(eur(1_000));
        assertThat(points.get(4).cumulative()).isEqualTo(eur(1_500));
        assertThat(points.get(30).date()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(points.get(30).cumulative()).isEqualTo(eur(1_500));
    }

    @Test
    void future_days_of_the_current_period_are_not_invented() {
        List<DayPoint> points = ChartSeriesService.series(PERIOD, LocalDate.of(2026, 3, 10),
                report(100, new DaySpending(LocalDate.of(2026, 3, 3), eur(100))));

        assertThat(points).hasSize(10);
        assertThat(points.get(9).date()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void a_future_period_has_no_points() {
        assertThat(ChartSeriesService.series(PERIOD, LocalDate.of(2026, 2, 28), report(0))).isEmpty();
    }

    @Test
    void a_refund_day_lowers_the_cumulative_and_the_end_boundary_is_exclusive() {
        List<DayPoint> points = ChartSeriesService.series(PERIOD, LocalDate.of(2026, 12, 1),
                report(700, new DaySpending(LocalDate.of(2026, 3, 1), eur(1_000)),
                        new DaySpending(LocalDate.of(2026, 3, 2), eur(-300)),
                        new DaySpending(LocalDate.of(2026, 4, 1), eur(9_999)))); // outside [start, end)

        assertThat(points.get(1).cumulative()).isEqualTo(eur(700));
        assertThat(points).hasSize(31);
        assertThat(points.get(30).cumulative()).isEqualTo(eur(700));
    }
}
