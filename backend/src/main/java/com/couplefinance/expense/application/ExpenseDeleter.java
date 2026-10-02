package com.couplefinance.expense.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.couplefinance.expense.api.ExpenseDeleted;
import com.couplefinance.expense.api.ExpenseRestored;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseAuditLog;
import com.couplefinance.expense.domain.ExpenseErrorCode;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.error.ApplicationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logical deletion and restoration of an expense (BR-EXP-03, BR-EXP-09..11, BR-HH-10). Each operation is ONE
 * transaction: the row is locked FOR UPDATE, the state changed, the audit entry appended and the event published.
 * No external call is made inside it. The household and the actor come from the authenticated user; every lookup
 * is scoped by that household and by visibility, so another household's expense and the partner's PERSONAL expense
 * are the same 404 as an unknown one.
 *
 * <p>Lock order is always "original of a refund, then the refund", as in refund creation and expense edits, so
 * concurrent operations cannot deadlock; refund creation locks the original, which serialises the BR-EXP-03
 * checks against it.
 */
@Service
public class ExpenseDeleter {

    private final CurrentHousehold currentHousehold;
    private final HouseholdLedgerRules ledgerRules;
    private final ExpenseRepository expenses;
    private final ExpenseAuditLog audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ExpenseDeleter(CurrentHousehold currentHousehold, HouseholdLedgerRules ledgerRules, ExpenseRepository expenses,
            ExpenseAuditLog audit, ApplicationEventPublisher events, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.ledgerRules = ledgerRules;
        this.expenses = expenses;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void delete(UUID expenseId) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        UUID household = context.householdId().value();
        UUID user = context.userId().value();

        Expense expense = expenses.lockVisibleLiveById(expenseId, household, user)
                .orElseThrow(ExpenseDeleter::notFound);
        // BR-EXP-03, under the lock on the expense: refund creation locks the original, so no refund can be
        // inserted between this check and the commit.
        expense.delete(context.userId(), clock, expenses.hasLiveRefunds(expense.id(), household));
        Expense saved = expenses.saveAndFlush(expense);
        audit.deleted(saved, context.userId());
        // BR-NOT-02: the creator is disclosed only for a SHARED expense (to inform the partner, BR-NOT-03).
        UUID creator = saved.ownerUserId() == null ? saved.createdBy() : null;
        events.publishEvent(new ExpenseDeleted(saved.id(), saved.householdId(), saved.ownerUserId(), user, creator,
                saved.deletedAt()));
    }

    @Transactional
    public ExpenseView restore(UUID expenseId) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        UUID household = context.householdId().value();
        UUID user = context.userId().value();
        LedgerProfile ledger = ledgerRules.profile(context.householdId());

        Expense peek = expenses.findVisibleDeletedById(expenseId, household, user)
                .orElseThrow(ExpenseDeleter::notFound);
        Expense original = null;
        if (peek.refundOfExpenseId() != null) {
            // BR-EXP-03: lock the original first (a deleted original cannot hold live refunds, so this is empty
            // only when the original itself is deleted).
            original = expenses.lockVisibleLiveById(peek.refundOfExpenseId(), household, user).orElse(null);
        }
        Expense expense = expenses.lockVisibleDeletedById(expenseId, household, user)
                .orElseThrow(ExpenseDeleter::notFound);
        long others = original == null ? 0 : expenses.sumLiveRefundsMinor(original.id(), household);
        Instant deletedAt = expense.deletedAt();
        expense.restore(context.userId(), clock, ledger, original, others);
        Expense saved = expenses.saveAndFlush(expense);
        audit.restored(saved, context.userId(), deletedAt);
        events.publishEvent(new ExpenseRestored(saved.id(), saved.householdId(), saved.ownerUserId(),
                saved.updatedAt()));
        return ExpenseView.of(saved, ledger.decimals());
    }

    private static ApplicationException notFound() {
        return new ApplicationException(ExpenseErrorCode.EXPENSE_NOT_FOUND, "The expense was not found.");
    }
}
