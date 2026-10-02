package com.couplefinance.expense.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.couplefinance.shared.money.Money;

/**
 * Net spending of one scope over {@code [start, end)} (BR-SCP-01/02/03). Totals are net: EXPENSE minus REFUND,
 * TRANSFER excluded, deleted expenses excluded. A net figure may be negative and is returned as-is (BR-ANA-06 is
 * the business of analytics). A breakdown that was not requested is an empty list.
 *
 * @param scope total's scope, always set (BR-SCP-03)
 * @param start inclusive start date
 * @param end   exclusive end date
 */
public record SpendingReport(SpendingScope scope, LocalDate start, LocalDate end, Money total,
        List<CategorySpending> byCategory, List<PayerSpending> byPayer, List<DaySpending> byDay) {

    public SpendingReport {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(total, "total");
        byCategory = List.copyOf(byCategory);
        byPayer = List.copyOf(byPayer);
        byDay = List.copyOf(byDay);
    }

    /** Net spending of the items of one category. */
    public record CategorySpending(UUID categoryId, Money total) {}

    /** Net spending paid by one member (paid-by, BR-ANA-04). */
    public record PayerSpending(UUID userId, Money total) {}

    /** Net spending dated on one day. */
    public record DaySpending(LocalDate date, Money total) {}
}
