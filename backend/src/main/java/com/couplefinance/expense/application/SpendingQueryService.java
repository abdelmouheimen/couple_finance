package com.couplefinance.expense.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.couplefinance.expense.api.SpendingGrouping;
import com.couplefinance.expense.api.SpendingQuery;
import com.couplefinance.expense.api.SpendingReport;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.expense.domain.ExpenseErrorCode;
import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.expense.domain.SpendingAggregates;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the spending facade (BR-SCP-01..03). Scope and user come from the caller's context only. One
 * read-only transaction gives a consistent snapshot across the total and its breakdowns.
 */
@Service
class SpendingQueryService implements SpendingQuery {

    private final HouseholdLedgerRules ledgerRules;
    private final SpendingAggregates aggregates;

    SpendingQueryService(HouseholdLedgerRules ledgerRules, SpendingAggregates aggregates) {
        this.ledgerRules = ledgerRules;
        this.aggregates = aggregates;
    }

    @Override
    @Transactional(readOnly = true)
    public SpendingReport spending(HouseholdContext context, SpendingScope scope, LocalDate start, LocalDate end,
            Set<SpendingGrouping> groupings) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(groupings, "groupings");
        if (!end.isAfter(start)) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_FILTER_INVALID,
                    "The end of the period must be after its start.");
        }
        LedgerProfile ledger = ledgerRules.profile(context.householdId());
        SharingType sharing = scope == SpendingScope.PERSONAL ? SharingType.PERSONAL : SharingType.SHARED;
        SpendingAggregates.Criteria criteria = new SpendingAggregates.Criteria(context.householdId().value(),
                sharing, context.userId().value(), start, end);

        Money total = money(aggregates.total(criteria), ledger);
        List<SpendingReport.CategorySpending> byCategory = groupings.contains(SpendingGrouping.CATEGORY)
                ? aggregates.byCategory(criteria).stream()
                        .map(e -> new SpendingReport.CategorySpending(e.key(), money(e.netMinor(), ledger))).toList()
                : List.of();
        List<SpendingReport.PayerSpending> byPayer = groupings.contains(SpendingGrouping.PAYER)
                ? aggregates.byPayer(criteria).stream()
                        .map(e -> new SpendingReport.PayerSpending(e.key(), money(e.netMinor(), ledger))).toList()
                : List.of();
        List<SpendingReport.DaySpending> byDay = groupings.contains(SpendingGrouping.DAY)
                ? aggregates.byDay(criteria).stream()
                        .map(e -> new SpendingReport.DaySpending(e.key(), money(e.netMinor(), ledger))).toList()
                : List.of();
        return new SpendingReport(scope, start, end, total, byCategory, byPayer, byDay);
    }

    private static Money money(long minor, LedgerProfile ledger) {
        return Money.ofMinor(minor, ledger.currency(), ledger.decimals());
    }
}
