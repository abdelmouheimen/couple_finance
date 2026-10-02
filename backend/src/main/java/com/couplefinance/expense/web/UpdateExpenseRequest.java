package com.couplefinance.expense.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.application.CreateExpenseCommand;
import com.couplefinance.expense.application.UpdateExpenseCommand;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseItem;
import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /api/v1/expenses/{id}}: the full new editable state of the expense. Kind, refund link,
 * source, creator and household are not editable and not accepted: unknown JSON properties are rejected (mass
 * assignment); the editor comes from the authenticated user. An omitted merchant or note is cleared.
 */
@Schema(name = "UpdateExpenseRequest")
record UpdateExpenseRequest(
        @Schema(description = "Total amount, in the household currency, strictly positive.",
                implementation = MoneySchema.class)
        @NotNull Money amount,

        @Schema(description = "Calendar date (YYYY-MM-DD). The window of 1 day ahead and 5 years back is checked "
                + "only when the date changes. A linked refund stays dated on or after its original, and an "
                + "original on or before its refunds.", example = "2026-05-14")
        @NotNull LocalDate date,

        @Schema(description = "Category breakdown: 1 to 10 items, one per category, summing exactly to the amount. "
                + "An item identical (category, amount, label) to a current item stays valid on a category "
                + "archived since; a changed or new item needs an active category.")
        @NotEmpty @Size(max = Expense.MAX_ITEMS) List<@NotNull @Valid Item> items,

        @Schema(description = "A current member of the household (checked when it changes). Must be the caller "
                + "for a PERSONAL expense.")
        @NotNull UUID paidByUserId,

        @Schema(description = "SHARED or PERSONAL. Changing it is reserved to the payer, who must also be the "
                + "creator to make it PERSONAL; a refund keeps the "
                + "sharing type of its original.")
        @NotNull SharingType sharingType,

        @Schema(description = "Merchant name, stored as given; omitted clears it.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(max = Expense.MERCHANT_MAX_LENGTH) String merchant,

        @Schema(description = "Omitted clears it.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(max = Expense.NOTE_MAX_LENGTH) String note) {

    UpdateExpenseCommand toCommand(UUID expenseId, @Nullable String ifMatch) {
        return new UpdateExpenseCommand(expenseId, ifMatch, amount, date,
                items.stream().map(item -> new CreateExpenseCommand.Item(item.categoryId(), item.amount(),
                        item.label())).toList(),
                paidByUserId, sharingType, merchant, note);
    }

    /** One category item. */
    @Schema(name = "UpdateExpenseItemRequest")
    record Item(
            @NotNull UUID categoryId,
            @Schema(description = "Strictly positive, in the household currency.", implementation = MoneySchema.class)
            @NotNull Money amount,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable @Size(max = ExpenseItem.LABEL_MAX_LENGTH) String label) {
    }
}
