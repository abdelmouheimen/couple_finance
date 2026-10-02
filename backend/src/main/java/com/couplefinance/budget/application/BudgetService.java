package com.couplefinance.budget.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.budget.api.BudgetCreated;
import com.couplefinance.budget.api.BudgetUpdated;
import com.couplefinance.budget.domain.Budget;
import com.couplefinance.budget.domain.BudgetAuditLog;
import com.couplefinance.budget.domain.BudgetErrorCode;
import com.couplefinance.budget.domain.BudgetRepository;
import com.couplefinance.budget.domain.BudgetStore;
import com.couplefinance.budget.domain.LimitConsumption;
import com.couplefinance.expense.api.SpendingQuery;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.household.api.BudgetPeriods;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.concurrency.VersionETag;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases on the budget of a period (BR-BUD-01, BR-BUD-02, BR-BUD-07). The household and the actor come from the
 * authenticated user; every lookup is scoped by that household, so another household's budget never exists for
 * the caller. The write is ONE transaction - budget row, audit entry and event - with no external call inside.
 */
@Service
public class BudgetService {

    private final CurrentHousehold currentHousehold;
    private final BudgetPeriods periods;
    private final HouseholdLedgerRules ledgerRules;
    private final BudgetRepository budgets;
    private final BudgetStore store;
    private final BudgetAuditLog audit;
    private final ApplicationEventPublisher events;
    private final SpendingQuery spending;
    private final Clock clock;

    BudgetService(CurrentHousehold currentHousehold, BudgetPeriods periods, HouseholdLedgerRules ledgerRules,
            BudgetRepository budgets, BudgetStore store, BudgetAuditLog audit, ApplicationEventPublisher events,
            SpendingQuery spending, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.periods = periods;
        this.ledgerRules = ledgerRules;
        this.budgets = budgets;
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.spending = spending;
        this.clock = clock;
    }

    /** Reads the budget of the period starting at {@code periodStart}; allowed to archive readers. */
    @Transactional(readOnly = true)
    public BudgetView get(LocalDate periodStart) {
        HouseholdContext context = currentHousehold.currentHousehold();
        requirePeriod(context, periodStart);
        UUID household = context.householdId().value();
        Budget budget = budgets.findByPeriod(household, periodStart).orElseThrow(
                () -> new ApplicationException(BudgetErrorCode.BUDGET_NOT_FOUND,
                        "There is no budget for this period."));
        int decimals = ledgerRules.profile(context.householdId()).decimals();
        return BudgetView.of(budget, decimals, overallConsumption(context, budget, decimals));
    }

    /**
     * BR-BUD-03/05/06: household spending of the period, read only through the spending query API; computed on
     * read, never stored. PERSONAL spending is excluded by the HOUSEHOLD scope.
     */
    private @Nullable LimitConsumption overallConsumption(HouseholdContext context, Budget budget, int decimals) {
        Long limit = budget.overallLimitMinor();
        if (limit == null) {
            return null;
        }
        Money consumed = spending.spending(context, SpendingScope.HOUSEHOLD, budget.periodStart(),
                budget.periodEnd(), Set.of()).total();
        return LimitConsumption.of(Money.ofMinor(limit, budget.currency(), decimals), consumed);
    }

    /**
     * Creates the budget of the period (no {@code If-Match}) or updates its overall limit ({@code If-Match}
     * required: 428 when absent, 412 when stale).
     */
    @Transactional
    public SetBudgetResult set(SetBudgetCommand command) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        BudgetPeriod period = requirePeriod(context, command.periodStart());
        UUID household = context.householdId().value();
        LedgerProfile ledger = ledgerRules.profile(context.householdId());
        // Request validation first: an invalid body is a 400 whatever the state of the budget (BR-BUD-02).
        Budget.validLimit(command.overallLimit(), ledger.currency(), ledger.decimals());

        Budget existing = budgets.lockByPeriod(household, period.start()).orElse(null);
        if (existing == null) {
            return create(context, period, ledger, command);
        }
        VersionETag.requireMatch(VersionETag.requireIfMatch(command.ifMatch()), existing.version());
        Long before = existing.overallLimitMinor();
        if (existing.setOverallLimit(command.overallLimit(), ledger.decimals(), clock, context.userId())) {
            Budget saved = budgets.saveAndFlush(existing);
            audit.overallLimitChanged(saved, before, context.userId());
            events.publishEvent(new BudgetUpdated(saved.id(), saved.householdId(), saved.periodStart(),
                    saved.updatedAt()));
            existing = saved;
        }
        return new SetBudgetResult(BudgetView.of(existing, ledger.decimals()), false);
    }

    private SetBudgetResult create(HouseholdContext context, BudgetPeriod period, LedgerProfile ledger,
            SetBudgetCommand command) {
        if (command.ifMatch() != null && !command.ifMatch().isBlank()) {
            // If-Match names a version of a budget that does not exist: the precondition fails (RFC 9110).
            throw VersionETag.versionConflict();
        }
        Budget budget = Budget.create(context.householdId(), period, ledger.currency(), ledger.decimals(), clock,
                command.overallLimit(), context.userId());
        if (!store.insertIfAbsent(budget)) {
            // A concurrent request created the budget first (uq_budget_period): this caller did not hold its
            // version, so its "create" precondition no longer holds.
            throw VersionETag.versionConflict();
        }
        Budget saved = budgets.findByPeriod(context.householdId().value(), period.start()).orElseThrow();
        audit.created(saved, context.userId());
        events.publishEvent(new BudgetCreated(saved.id(), saved.householdId(), saved.periodStart(),
                saved.createdAt()));
        return new SetBudgetResult(BudgetView.of(saved, ledger.decimals()), true);
    }

    /** BR-HH-07: the path date must be the start of an existing period of the caller's household calendar. */
    private BudgetPeriod requirePeriod(HouseholdContext context, LocalDate periodStart) {
        return periods.findPeriodContaining(context, periodStart).filter(p -> p.start().equals(periodStart))
                .orElseThrow(() -> new ApplicationException(BudgetErrorCode.BUDGET_PERIOD_NOT_FOUND,
                        "There is no budget period starting on this date."));
    }
}
