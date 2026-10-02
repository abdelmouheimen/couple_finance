package com.couplefinance.budget.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.couplefinance.budget.application.BudgetView;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The budget of a period as returned by the API. Holds limits only; consumption is not part of it. */
@Schema(name = "Budget", requiredProperties = {"id", "periodStart", "periodEnd", "createdBy", "createdAt",
        "updatedBy", "updatedAt", "version"})
record BudgetResponse(
        UUID id,
        @Schema(description = "First day of the period (inclusive).") LocalDate periodStart,
        @Schema(description = "End of the period (exclusive): the start of the next period.") LocalDate periodEnd,
        @Schema(implementation = MoneySchema.class, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable Money overallLimit,
        UUID createdBy,
        Instant createdAt,
        UUID updatedBy,
        Instant updatedAt,
        @Schema(description = "Version, also sent as ETag.") long version) {

    static BudgetResponse from(BudgetView budget) {
        return new BudgetResponse(budget.id(), budget.periodStart(), budget.periodEnd(), budget.overallLimit(),
                budget.createdBy(), budget.createdAt(), budget.updatedBy(), budget.updatedAt(), budget.version());
    }
}
