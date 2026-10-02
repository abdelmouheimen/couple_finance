package com.couplefinance.expense.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.application.CreateExpenseCommand;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseItem;
import com.couplefinance.expense.domain.ExpenseKind;
import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/expenses}. Intentionally has no household, creator or owner field: they come from
 * the authenticated user, and unknown JSON properties are rejected (mass assignment).
 */
@Schema(name = "CreateExpenseRequest")
record CreateExpenseRequest(
        @Schema(description = "EXPENSE (default) or REFUND.", defaultValue = "EXPENSE",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable ExpenseKind kind,

        @Schema(description = "Total amount, in the household currency, strictly positive.",
                implementation = MoneySchema.class)
        @NotNull Money amount,

        @Schema(description = "Calendar date of the expense (YYYY-MM-DD): at most 1 day after today in the "
                + "household time zone, at most 5 years old. A linked refund is dated on or after its original.",
                example = "2026-05-14")
        @NotNull LocalDate date,

        @Schema(description = "Category breakdown: 1 to 10 items, one per category, summing exactly to the amount. "
                + "May be omitted only for a REFUND with refundOf: the items then default to the original "
                + "categories, proportionally.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(max = Expense.MAX_ITEMS) List<@NotNull @Valid Item> items,

        @Schema(description = "A current member of the household. Must be the caller for a PERSONAL expense.")
        @NotNull UUID paidByUserId,

        @Schema(description = "SHARED (default) or PERSONAL (visible only to its owner, the caller). A refund with "
                + "refundOf has the sharing type of its original; another value is rejected.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable SharingType sharingType,

        @Schema(description = "Merchant name, stored as given.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(max = Expense.MERCHANT_MAX_LENGTH) String merchant,

        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(max = Expense.NOTE_MAX_LENGTH) String note,

        @Schema(description = "Original EXPENSE of the same household that the caller can see, for a REFUND only. "
                + "Unknown and invisible originals are both a 404.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable UUID refundOf) {

    /** One category item. */
    @Schema(name = "CreateExpenseItemRequest")
    record Item(
            @Schema(description = "A system category or a category of the household, not archived.")
            @NotNull UUID categoryId,
            @Schema(description = "Strictly positive, in the household currency.", implementation = MoneySchema.class)
            @NotNull Money amount,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable @Size(max = ExpenseItem.LABEL_MAX_LENGTH) String label) {
    }

    CreateExpenseCommand toCommand() {
        return new CreateExpenseCommand(kind == null ? ExpenseKind.EXPENSE : kind, amount, date,
                items == null ? null : items.stream().map(item -> new CreateExpenseCommand.Item(item.categoryId(),
                        item.amount(), item.label())).toList(),
                paidByUserId, sharingType, merchant, note, refundOf);
    }
}
