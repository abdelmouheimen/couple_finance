package com.couplefinance.expense.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * Input of the update-expense use case (BR-EXP-09): the full new editable state. It carries no household or
 * editor: both come from the authenticated user.
 *
 * @param ifMatch the raw {@code If-Match} header (BR-EXP-12)
 */
public record UpdateExpenseCommand(UUID expenseId, @Nullable String ifMatch, Money amount, LocalDate date,
        List<CreateExpenseCommand.Item> items, UUID paidByUserId, SharingType sharingType,
        @Nullable String merchant, @Nullable String note) {
}
