package com.couplefinance.budget.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import com.couplefinance.budget.api.BudgetSummaries;
import com.couplefinance.budget.api.BudgetSummary;
import com.couplefinance.budget.domain.BudgetRepository;
import com.couplefinance.budget.domain.LimitConsumption;
import com.couplefinance.expense.api.SpendingQuery;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link BudgetSummaries}: BR-BUD-03/05/06 consumption of the overall limit, read-only and household-scoped. */
@Service
class BudgetSummaryService implements BudgetSummaries {

    private final BudgetRepository budgets;
    private final HouseholdLedgerRules ledgerRules;
    private final SpendingQuery spending;

    BudgetSummaryService(BudgetRepository budgets, HouseholdLedgerRules ledgerRules, SpendingQuery spending) {
        this.budgets = budgets;
        this.ledgerRules = ledgerRules;
        this.spending = spending;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BudgetSummary> overallSummary(HouseholdContext context, LocalDate periodStart) {
        return budgets.findByPeriod(context.householdId().value(), periodStart)
                .filter(budget -> budget.overallLimitMinor() != null).map(budget -> {
                    int decimals = ledgerRules.profile(context.householdId()).decimals();
                    Money consumed = spending.spending(context, SpendingScope.HOUSEHOLD, budget.periodStart(),
                            budget.periodEnd(), Set.of()).total();
                    LimitConsumption c = LimitConsumption.of(
                            Money.ofMinor(budget.overallLimitMinor(), budget.currency(), decimals), consumed);
                    return new BudgetSummary(c.limit(), c.consumed(), c.remaining(), c.percentage(),
                            BudgetSummary.Status.valueOf(c.status().name()));
                });
    }
}
