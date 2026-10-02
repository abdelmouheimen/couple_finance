package com.couplefinance.expense.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.application.ExpenseView;
import com.couplefinance.expense.domain.ExpenseKind;
import com.couplefinance.expense.domain.ExpenseSource;
import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** An expense as returned by the API. */
@Schema(name = "Expense", requiredProperties = {"id", "kind", "amount", "date", "paidByUserId", "sharingType",
        "items", "source", "createdBy", "createdAt", "version"})
record ExpenseResponse(
        UUID id,
        ExpenseKind kind,
        @Schema(implementation = MoneySchema.class) Money amount,
        LocalDate date,
        UUID paidByUserId,
        SharingType sharingType,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String merchant,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String note,
        @Schema(description = "The original expense of a linked REFUND.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable UUID refundOf,
        List<Item> items,
        ExpenseSource source,
        UUID createdBy,
        Instant createdAt,
        @Schema(description = "Version, also sent as ETag.") long version) {

    /** One category item. */
    @Schema(name = "ExpenseItem", requiredProperties = {"id", "position", "categoryId", "amount"})
    record Item(UUID id, int position, UUID categoryId,
            @Schema(implementation = MoneySchema.class) Money amount,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String label) {
    }

    static ExpenseResponse from(ExpenseView expense) {
        return new ExpenseResponse(expense.id(), expense.kind(), expense.amount(), expense.date(),
                expense.paidByUserId(), expense.sharingType(), expense.merchant(), expense.note(),
                expense.refundOf(),
                expense.items().stream().map(item -> new Item(item.id(), item.position(), item.categoryId(),
                        item.amount(), item.label())).toList(),
                expense.source(), expense.createdBy(), expense.createdAt(), expense.version());
    }
}
