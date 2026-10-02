package com.couplefinance.budget.web;

import com.couplefinance.budget.domain.BudgetStatus;
import com.couplefinance.budget.domain.LimitConsumption;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/** Consumption of one limit, computed on read from household spending (BR-BUD-03, BR-BUD-05, BR-BUD-06). */
@Schema(name = "LimitConsumption")
record LimitConsumptionResponse(
        @Schema(allowableValues = "HOUSEHOLD", description = "Always HOUSEHOLD: PERSONAL spending is never included (BR-SCP-01, BR-SCP-03).")
        SpendingScope scope,
        @Schema(implementation = MoneySchema.class) Money consumed,
        @Schema(implementation = MoneySchema.class, description = "limit - consumed; negative when exceeded.")
        Money remaining,
        @Schema(description = "consumed / limit in percent, one decimal, HALF_EVEN (BR-MON-09); a string like "
                + "Money amounts.", example = "82.5") String percentage,
        @Schema(description = "ON_TRACK below 80.0%, WARNING from 80.0% to 100.0% inclusive, EXCEEDED above "
                + "100%.") BudgetStatus status) {

    static LimitConsumptionResponse from(LimitConsumption c) {
        return new LimitConsumptionResponse(SpendingScope.HOUSEHOLD, c.consumed(), c.remaining(),
                c.percentage().toPlainString(), c.status());
    }
}
