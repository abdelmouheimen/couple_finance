package com.couplefinance.expense.application;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.categorization.api.CategoryCatalogue;
import com.couplefinance.expense.api.ExpenseCreated;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseAuditLog;
import com.couplefinance.expense.domain.ExpenseErrorCode;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.expense.domain.NewItem;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UserId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional unit of expense creation: expense, items, audit entry and {@code ExpenseCreated} are written
 * in ONE transaction (architecture.md section 6.1). No external call is made inside it. The household and the
 * creator come from the authenticated user; every lookup is scoped by that household.
 */
@Service
class ExpenseCreator {

    private final CurrentHousehold currentHousehold;
    private final HouseholdLedgerRules ledgerRules;
    private final CategoryCatalogue categories;
    private final ExpenseRepository expenses;
    private final ExpenseAuditLog audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ExpenseCreator(CurrentHousehold currentHousehold, HouseholdLedgerRules ledgerRules, CategoryCatalogue categories,
            ExpenseRepository expenses, ExpenseAuditLog audit, ApplicationEventPublisher events, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.ledgerRules = ledgerRules;
        this.categories = categories;
        this.expenses = expenses;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    ExpenseView create(CreateExpenseCommand command) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        LedgerProfile ledger = ledgerRules.profile(context.householdId());

        List<NewItem> items = command.items().stream()
                .map(item -> new NewItem(item.categoryId(), item.amount(), item.label())).toList();
        UserId paidBy = new UserId(command.paidByUserId());
        Expense expense = Expense.create(context.householdId(), ledger, clock, command.amount(), command.date(),
                items, paidBy, command.sharingType(), context.userId(), command.merchant(), command.note());

        if (!ledgerRules.isActiveMember(context.householdId(), paidBy)) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_PAID_BY_INVALID,
                    "The payer must be a member of the household.");
        }
        requireUsableCategories(context, items);

        Expense saved = expenses.saveAndFlush(expense);
        audit.created(saved, context.userId());
        events.publishEvent(new ExpenseCreated(saved.id(), saved.householdId(), saved.ownerUserId(),
                saved.createdAt()));
        return ExpenseView.of(saved, ledger.decimals());
    }

    /** BR-EXP-14: unknown or foreign categories are a 404 (non-disclosure), archived ones a 400. */
    private void requireUsableCategories(HouseholdContext context, List<NewItem> items) {
        Set<UUID> ids = Set.copyOf(items.stream().map(NewItem::categoryId).toList());
        if (!categories.unknownCategories(context.householdId(), ids).isEmpty()) {
            throw new ApplicationException(ExpenseErrorCode.CATEGORY_NOT_FOUND, "The category was not found.");
        }
        if (!categories.unusableCategories(context.householdId(), ids).isEmpty()) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_CATEGORY_ARCHIVED,
                    "An archived category cannot be used on a new expense.");
        }
    }
}
