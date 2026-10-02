package com.couplefinance.expense.web;

import java.util.List;

import com.couplefinance.expense.application.ExpensePage;
import com.couplefinance.expense.domain.ExpenseScope;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** A page of the expense history with the totals of the whole filtered set. */
@Schema(name = "ExpensePage", requiredProperties = {"items", "totals"})
record ExpensePageResponse(
        List<ExpenseResponse> items,
        @Schema(description = "Opaque cursor of the next page, absent on the last page.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String nextCursor,
        Totals totals) {

    /** Server-computed totals of every expense matching the filters, not only of this page (BR-SCP-03). */
    @Schema(name = "ExpenseTotals", requiredProperties = {"scope", "count", "net"})
    record Totals(
            @Schema(description = "Scope of the figures: HOUSEHOLD (SHARED expenses) or PERSONAL (caller's own).")
            ExpenseScope scope,
            @Schema(description = "Number of matching expenses, all kinds.") long count,
            @Schema(description = "EXPENSE minus REFUND (TRANSFER excluded, BR-SCP-01); negative when refunds "
                    + "exceed expenses. With a category filter, only the items of that category.",
                    implementation = MoneySchema.class) Money net) {
    }

    static ExpensePageResponse from(ExpensePage page) {
        return new ExpensePageResponse(page.items().stream().map(ExpenseResponse::from).toList(),
                page.nextCursor(),
                new Totals(page.totals().scope(), page.totals().count(), page.totals().net()));
    }
}
