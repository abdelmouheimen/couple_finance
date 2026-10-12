package com.couplefinance.expense.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.expense.api.ReceiptExpenses;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseErrorCode;
import com.couplefinance.expense.domain.ExpenseKind;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.Money;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReceiptExpenses} for the receipt module (ADR-006, BR-RCP-15). The commands reuse the manual-expense use
 * case, so an expense confirmed from a receipt obeys exactly the same rules; they join the caller's transaction.
 */
@Service
class ReceiptExpenseService implements ReceiptExpenses {

    /** Upper bound of duplicate candidates returned (BR-RCP-14 is advisory). */
    static final int MAX_SIMILAR = 10;

    private final ExpenseCreator creator;
    private final CurrentHousehold currentHousehold;
    private final ExpenseRepository expenses;

    ReceiptExpenseService(ExpenseCreator creator, CurrentHousehold currentHousehold, ExpenseRepository expenses) {
        this.creator = creator;
        this.currentHousehold = currentHousehold;
        this.expenses = expenses;
    }

    @Override
    @Transactional
    public UUID createFromReceipt(NewReceiptExpense expense) {
        CreateExpenseCommand command = new CreateExpenseCommand(
                expense.refund() ? ExpenseKind.REFUND : ExpenseKind.EXPENSE, expense.amount(), expense.date(),
                List.of(new CreateExpenseCommand.Item(expense.categoryId(), expense.amount(), null)),
                expense.paidByUserId(), expense.personal() ? SharingType.PERSONAL : SharingType.SHARED,
                expense.merchant(), expense.note(), null);
        return creator.create(command, expense.receiptId()).id();
    }

    @Override
    @Transactional
    public void attachReceipt(UUID expenseId, UUID receiptId) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        Expense expense = expenses.lockVisibleLiveById(expenseId, context.householdId().value(),
                context.userId().value())
                .orElseThrow(() -> new ApplicationException(ExpenseErrorCode.EXPENSE_NOT_FOUND,
                        "The expense was not found."));
        expense.attachReceipt(receiptId, context.userId());
        expenses.saveAndFlush(expense);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> visibleExpenseOf(HouseholdId household, UserId viewer, UUID receiptId) {
        return expenses.findVisibleLiveIdByReceiptId(receiptId, household.value(), viewer.value());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UUID> visibleLinks(HouseholdId household, UserId viewer) {
        Map<UUID, UUID> links = new LinkedHashMap<>();
        for (ExpenseRepository.ReceiptLink link : expenses.findVisibleReceiptLinks(household.value(),
                viewer.value())) {
            links.put(link.receiptId(), link.expenseId());
        }
        return links;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SimilarExpense> similar(HouseholdId household, UserId viewer, Money amount, LocalDate date) {
        return expenses.findVisibleLiveByAmountAndDate(household.value(), viewer.value(), amount.minorUnits(),
                amount.currency().value(), date.minusDays(1), date.plusDays(1), Limit.of(MAX_SIMILAR)).stream()
                .map(e -> new SimilarExpense(e.id(), e.expenseDate(), e.merchantKey(), e.receiptId() != null))
                .toList();
    }
}
