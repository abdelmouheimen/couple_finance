package com.couplefinance.budget.web;

import java.time.LocalDate;
import java.util.List;

import com.couplefinance.budget.application.SetBudgetCommand;
import com.couplefinance.budget.domain.CategoryLimits;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /api/v1/budgets/{periodStart}}. Household, period end, currency and actor are not accepted:
 * unknown JSON properties are rejected (mass assignment); they come from the calendar and the authenticated user.
 */
@Schema(name = "SetBudgetRequest")
record SetBudgetRequest(
        @Schema(description = "Overall spending limit of the period, in the household currency, strictly "
                + "positive. Omitting it clears it, which is allowed only while the budget keeps at least one "
                + "category limit (BR-BUD-02): otherwise 400 and nothing is stored.", implementation = MoneySchema.class,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable Money overallLimit,
        @Schema(description = "The complete set of category limits of the period: it replaces the existing lines "
                + "(added, changed and omitted lines are applied). Omitted: the existing lines are left untouched; "
                + "an empty array removes them all. At most one line per category (BR-BUD-01). Their sum may exceed "
                + "the overall limit: the response then carries categoryLimitsWarning (BR-BUD-04).",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(max = CategoryLimits.MAX_LINES) List<@NotNull @Valid CategoryLimitRequest> categoryLimits) {

    SetBudgetCommand toCommand(LocalDate periodStart, @Nullable String ifMatch) {
        return new SetBudgetCommand(periodStart, overallLimit,
                categoryLimits == null ? null : categoryLimits.stream().map(CategoryLimitRequest::toDomain).toList(),
                ifMatch);
    }
}
