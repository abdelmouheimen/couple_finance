package com.couplefinance.expense.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** The editable state of an expense at a point in time (BR-EXP-10): the "old" side of an audit diff. */
public record ExpenseSnapshot(long amountMinor, String currency, LocalDate expenseDate, UUID paidByUserId,
        @Nullable UUID ownerUserId, @Nullable String merchant, @Nullable String note, List<Item> items) {

    /** One category item; the label is {@code null} when blank. */
    public record Item(UUID categoryId, long amountMinor, @Nullable String label) {}
}
