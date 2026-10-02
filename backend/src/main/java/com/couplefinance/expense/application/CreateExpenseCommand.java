package com.couplefinance.expense.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.domain.ExpenseKind;
import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * Input of the create-expense use case (BR-EXP-01, BR-EXP-03). It carries no household or creator: both come from
 * the authenticated user. {@code paidByUserId} is only a claim, verified against the household members.
 *
 * @param kind        {@code EXPENSE} or {@code REFUND}
 * @param items       {@code null} or empty is accepted only for a REFUND linked to an original, whose items then
 *                    default to the original categories (BR-EXP-03)
 * @param sharingType {@code null} means SHARED, except for a linked refund, where it means "that of the original"
 * @param refundOfExpenseId the original expense of a linked REFUND; only allowed for kind REFUND
 */
public record CreateExpenseCommand(ExpenseKind kind, Money amount, LocalDate date, @Nullable List<Item> items,
        UUID paidByUserId, @Nullable SharingType sharingType, @Nullable String merchant, @Nullable String note,
        @Nullable UUID refundOfExpenseId) {

    /** An {@code EXPENSE} command. */
    public CreateExpenseCommand(Money amount, LocalDate date, List<Item> items, UUID paidByUserId,
            SharingType sharingType, @Nullable String merchant, @Nullable String note) {
        this(ExpenseKind.EXPENSE, amount, date, items, paidByUserId, sharingType, merchant, note, null);
    }

    /** One category item (BR-EXP-08). */
    public record Item(UUID categoryId, Money amount, @Nullable String label) {}
}
