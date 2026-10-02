package com.couplefinance.expense.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * Input of the create-expense use case (BR-EXP-01). It carries no household or creator: both come from the
 * authenticated user. {@code paidByUserId} is only a claim, verified against the household members.
 */
public record CreateExpenseCommand(Money amount, LocalDate date, List<Item> items, UUID paidByUserId,
        SharingType sharingType, @Nullable String merchant, @Nullable String note) {

    /** One category item (BR-EXP-08). */
    public record Item(UUID categoryId, Money amount, @Nullable String label) {}
}
