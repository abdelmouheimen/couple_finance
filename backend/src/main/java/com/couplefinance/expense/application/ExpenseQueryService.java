package com.couplefinance.expense.application;

import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseAuditTrail;
import com.couplefinance.expense.domain.ExpenseErrorCode;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.shared.error.ApplicationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read use cases on one expense (BR-EXP-07, BR-EXP-10). The household and the user come from the authenticated
 * caller; archive readers of a dissolved household may read (BR-HH-10). An expense that does not exist, is deleted,
 * belongs to another household or is PERSONAL to the partner is one and the same 404.
 */
@Service
public class ExpenseQueryService {

    private final CurrentHousehold currentHousehold;
    private final HouseholdLedgerRules ledgerRules;
    private final ExpenseRepository expenses;
    private final ExpenseAuditTrail auditTrail;

    ExpenseQueryService(CurrentHousehold currentHousehold, HouseholdLedgerRules ledgerRules,
            ExpenseRepository expenses, ExpenseAuditTrail auditTrail) {
        this.currentHousehold = currentHousehold;
        this.ledgerRules = ledgerRules;
        this.expenses = expenses;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public ExpenseView get(UUID id) {
        HouseholdContext context = currentHousehold.currentHousehold();
        Expense expense = visible(id, context);
        return ExpenseView.of(expense, ledgerRules.profile(context.householdId()).decimals());
    }

    /** The audit trail (BR-EXP-10) of an expense the caller can view, oldest entry first. */
    @Transactional(readOnly = true)
    public List<AuditEntryView> audit(UUID id) {
        HouseholdContext context = currentHousehold.currentHousehold();
        Expense expense = visible(id, context);
        return auditTrail.history(expense.id(), context.householdId().value(), context.userId().value()).stream()
                .map(entry -> new AuditEntryView(entry.id(), entry.action(), entry.actorType(),
                        entry.actorUserId(), entry.actorSystem(), entry.occurredAt(), entry.changes()))
                .toList();
    }

    private Expense visible(UUID id, HouseholdContext context) {
        return expenses.findVisibleLiveById(id, context.householdId().value(), context.userId().value())
                .orElseThrow(() -> new ApplicationException(ExpenseErrorCode.EXPENSE_NOT_FOUND,
                        "The expense was not found."));
    }
}
