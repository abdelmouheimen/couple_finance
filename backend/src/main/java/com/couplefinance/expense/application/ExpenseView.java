package com.couplefinance.expense.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseItem;
import com.couplefinance.expense.domain.ExpenseKind;
import com.couplefinance.expense.domain.ExpenseSource;
import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * Read model of one expense returned by the use cases (never the JPA entity). It is also the body stored for
 * idempotent replay, so it must round-trip through JSON; amounts are {@link Money}.
 */
public record ExpenseView(UUID id, ExpenseKind kind, Money amount, LocalDate date, UUID paidByUserId,
        SharingType sharingType, @Nullable String merchant, @Nullable String note, List<ItemView> items,
        ExpenseSource source, UUID createdBy, Instant createdAt, long version) {

    /** One category item of the expense. */
    public record ItemView(UUID id, int position, UUID categoryId, Money amount, @Nullable String label) {}

    static ExpenseView of(Expense expense, int decimals) {
        List<ItemView> items = expense.items().stream()
                .map(item -> toView(item, expense, decimals)).toList();
        return new ExpenseView(expense.id(), expense.kind(),
                Money.ofMinor(expense.amountMinor(), expense.currency(), decimals), expense.expenseDate(),
                expense.paidByUserId(), expense.sharingType(), expense.merchantDisplay(), expense.note(), items,
                expense.source(), expense.createdBy(), expense.createdAt(), expense.version());
    }

    private static ItemView toView(ExpenseItem item, Expense expense, int decimals) {
        return new ItemView(item.id(), item.position(), item.categoryId(),
                Money.ofMinor(item.amountMinor(), expense.currency(), decimals), item.label());
    }
}
