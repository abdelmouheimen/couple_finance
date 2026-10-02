package com.couplefinance.budget.web;

import java.time.LocalDate;

import com.couplefinance.budget.application.SetBudgetCommand;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /api/v1/budgets/{periodStart}}. Household, period end, currency and actor are not accepted:
 * unknown JSON properties are rejected (mass assignment); they come from the calendar and the authenticated user.
 */
@Schema(name = "SetBudgetRequest")
record SetBudgetRequest(
        @Schema(description = "Overall spending limit of the period, in the household currency, strictly "
                + "positive. Required while a budget has no category limit (BR-BUD-02): omitting it is a 400 and "
                + "nothing is stored.", implementation = MoneySchema.class,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable Money overallLimit) {

    SetBudgetCommand toCommand(LocalDate periodStart, @Nullable String ifMatch) {
        return new SetBudgetCommand(periodStart, overallLimit, ifMatch);
    }
}
