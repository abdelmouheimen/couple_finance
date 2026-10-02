package com.couplefinance.expense.application;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.categorization.api.CategoryCatalogue;
import com.couplefinance.expense.api.ExpenseUpdated;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseAuditLog;
import com.couplefinance.expense.domain.ExpenseErrorCode;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.expense.domain.ExpenseSnapshot;
import com.couplefinance.expense.domain.NewItem;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.concurrency.VersionETag;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UserId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional unit of an expense edit (BR-EXP-09, BR-EXP-12): the row is locked FOR UPDATE, the version
 * compared, the expense and its items replaced, the audit entry appended and {@code ExpenseUpdated} published in
 * ONE transaction. No external call is made inside it. The household and the editor come from the authenticated
 * user; every lookup is scoped by that household and by visibility, so another household's expense and the
 * partner's PERSONAL expense are the same 404.
 */
@Service
public class ExpenseUpdater {

    private final CurrentHousehold currentHousehold;
    private final HouseholdLedgerRules ledgerRules;
    private final CategoryCatalogue categories;
    private final ExpenseRepository expenses;
    private final ExpenseAuditLog audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ExpenseUpdater(CurrentHousehold currentHousehold, HouseholdLedgerRules ledgerRules, CategoryCatalogue categories,
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
    public ExpenseView update(UpdateExpenseCommand command) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        UUID household = context.householdId().value();
        UUID user = context.userId().value();
        LedgerProfile ledger = ledgerRules.profile(context.householdId());

        Expense expense = lockInOrder(command.expenseId(), household, user);
        // The 404 above comes before 428/412: a caller never learns the version of an expense they cannot see.
        VersionETag.requireMatch(VersionETag.requireIfMatch(command.ifMatch()), expense.version());

        UserId paidBy = new UserId(command.paidByUserId());
        List<NewItem> items = command.items().stream()
                .map(item -> new NewItem(item.categoryId(), item.amount(), item.label())).toList();

        // Validation-time rules apply to what the edit changes (domain-model.md section 7.1, BR-EXP-14).
        if (!paidBy.value().equals(expense.paidByUserId())
                && !ledgerRules.isActiveMember(context.householdId(), paidBy)) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_PAID_BY_INVALID,
                    "The payer must be a member of the household.");
        }
        requireUsableCategories(context, expense.categoriesRequiringActiveStatus(items));

        ExpenseSnapshot before = expense.snapshot();
        boolean changed = expense.update(context.userId(), ledger, clock, command.amount(), command.date(), items,
                paidBy, command.sharingType(), command.merchant(), command.note(),
                refundState(expense, household, user));
        if (!changed) {
            return ExpenseView.of(expense, ledger.decimals());
        }
        Expense saved = expenses.saveAndFlush(expense);
        audit.updated(before, saved, context.userId());
        events.publishEvent(new ExpenseUpdated(saved.id(), saved.householdId(), before.ownerUserId(),
                saved.ownerUserId(), saved.updatedAt()));
        return ExpenseView.of(saved, ledger.decimals());
    }

    /**
     * Locks the original of a linked refund before the refund itself - the order in which refund creation locks
     * (the original only) - so that concurrent edits and refund creations cannot deadlock.
     */
    private Expense lockInOrder(UUID id, UUID household, UUID user) {
        Expense peek = expenses.findVisibleLiveById(id, household, user).orElseThrow(ExpenseUpdater::notFound);
        if (peek.refundOfExpenseId() != null) {
            expenses.lockVisibleLiveById(peek.refundOfExpenseId(), household, user)
                    .orElseThrow(ExpenseUpdater::notFound);
        }
        return expenses.lockVisibleLiveById(id, household, user).orElseThrow(ExpenseUpdater::notFound);
    }

    /** BR-EXP-03: the refund facts around the edited expense, read under the lock on the original. */
    private Expense.RefundState refundState(Expense expense, UUID household, UUID user) {
        if (expense.refundOfExpenseId() != null) {
            Expense original = expenses.lockVisibleLiveById(expense.refundOfExpenseId(), household, user)
                    .orElseThrow(ExpenseUpdater::notFound);
            long others = expenses.sumLiveRefundsMinor(original.id(), household) - expense.amountMinor();
            return new Expense.RefundState(original, others, 0, null);
        }
        long own = expenses.sumLiveRefundsMinor(expense.id(), household);
        if (own == 0) {
            return Expense.RefundState.none();
        }
        return new Expense.RefundState(null, 0, own,
                expenses.earliestLiveRefundDate(expense.id(), household).orElse(null));
    }

    /** BR-EXP-14: unknown or foreign categories are a 404 (non-disclosure), archived ones a 400. */
    private void requireUsableCategories(HouseholdContext context, Set<UUID> required) {
        if (required.isEmpty()) {
            return;
        }
        if (!categories.unknownCategories(context.householdId(), required).isEmpty()) {
            throw new ApplicationException(ExpenseErrorCode.CATEGORY_NOT_FOUND, "The category was not found.");
        }
        if (!categories.unusableCategories(context.householdId(), required).isEmpty()) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_CATEGORY_ARCHIVED,
                    "An archived category cannot be used on a changed or new item.");
        }
    }

    private static ApplicationException notFound() {
        return new ApplicationException(ExpenseErrorCode.EXPENSE_NOT_FOUND, "The expense was not found.");
    }
}
