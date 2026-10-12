package com.couplefinance.expense.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * What the receipt module may ask of the ledger (domain-model.md sections 6 and 7, ADR-006): a receipt proposes,
 * only a member creates an expense, and the single link is {@code Expense.receiptId}. The receipt module never
 * writes expense data itself.
 *
 * <p>The two commands act for the authenticated user, apply every rule of a manual expense and join the caller's
 * transaction, so the receipt transition and the expense link commit together or not at all (BR-RCP-15). The
 * queries are scoped by household and by the viewer's visibility (BR-EXP-07): a partner's PERSONAL expense is never
 * returned.
 */
public interface ReceiptExpenses {

    /**
     * The values a member confirmed for a receipt: one expense, one category for the whole amount.
     *
     * @param refund   {@code true} for a REFUND (BR-RCP-12), otherwise an EXPENSE
     * @param personal {@code true} for a PERSONAL expense of the caller, otherwise SHARED
     */
    record NewReceiptExpense(UUID receiptId, boolean refund, Money amount, LocalDate date, UUID categoryId,
            UUID paidByUserId, boolean personal, @Nullable String merchant, @Nullable String note) {
        public NewReceiptExpense {
            Objects.requireNonNull(receiptId, "receiptId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(categoryId, "categoryId");
            Objects.requireNonNull(paidByUserId, "paidByUserId");
        }
    }

    /** A live expense the viewer can see with the same amount around the same date (BR-RCP-14 c). */
    record SimilarExpense(UUID expenseId, LocalDate date, @Nullable String merchantKey, boolean hasReceipt) {
    }

    /**
     * Creates the expense confirmed from a receipt, exactly like a manual expense (same validation, audit entry and
     * {@code ExpenseCreated} event), with source RECEIPT and its {@code receiptId}.
     *
     * @return the id of the created expense
     */
    UUID createFromReceipt(NewReceiptExpense expense);

    /**
     * Links a receipt to an existing expense the caller can edit (never the partner's PERSONAL expense, which is
     * "not found"). An expense holds at most one receipt.
     */
    void attachReceipt(UUID expenseId, UUID receiptId);

    /** The live expense linked to this receipt, when the viewer can see it. */
    Optional<UUID> visibleExpenseOf(HouseholdId household, UserId viewer, UUID receiptId);

    /** Receipt id → expense id, for every receipt linked to a live expense the viewer can see. */
    Map<UUID, UUID> visibleLinks(HouseholdId household, UserId viewer);

    /** Live expenses the viewer can see with exactly this amount, dated within one day of {@code date}. */
    List<SimilarExpense> similar(HouseholdId household, UserId viewer, Money amount, LocalDate date);
}
