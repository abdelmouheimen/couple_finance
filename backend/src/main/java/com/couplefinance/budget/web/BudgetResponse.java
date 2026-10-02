package com.couplefinance.budget.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.couplefinance.budget.application.BudgetView;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The budget of a period as returned by the API. Limits, plus on a read the computed consumption. */
@Schema(name = "Budget", requiredProperties = {"id", "periodStart", "periodEnd", "createdBy", "createdAt",
        "updatedBy", "updatedAt", "version"})
record BudgetResponse(
        UUID id,
        @Schema(description = "First day of the period (inclusive).") LocalDate periodStart,
        @Schema(description = "End of the period (exclusive): the start of the next period.") LocalDate periodEnd,
        @Schema(implementation = MoneySchema.class, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable Money overallLimit,
        @Schema(description = "Consumption of the overall limit, computed on read from the household spending of "
                + "the period. Present on GET when an overall limit exists; absent in the response of a PUT.")
        @Nullable LimitConsumptionResponse overallConsumption,
        UUID createdBy,
        Instant createdAt,
        UUID updatedBy,
        Instant updatedAt,
        @Schema(description = "Version, also sent as ETag.") long version) {

    static BudgetResponse from(BudgetView budget) {
        LimitConsumptionResponse consumption = budget.overallConsumption() == null ? null
                : LimitConsumptionResponse.from(budget.overallConsumption());
        return new BudgetResponse(budget.id(), budget.periodStart(), budget.periodEnd(), budget.overallLimit(),
                consumption, budget.createdBy(), budget.createdAt(), budget.updatedBy(), budget.updatedAt(),
                budget.version());
    }
}
