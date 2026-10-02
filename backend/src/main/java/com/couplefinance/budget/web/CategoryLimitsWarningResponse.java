package com.couplefinance.budget.web;

import com.couplefinance.budget.domain.CategoryLimitsWarning;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/** BR-BUD-04: the category limits add up to more than the overall limit. Informational, never blocking. */
@Schema(name = "CategoryLimitsWarning", requiredProperties = {"categoryLimitsTotal", "overallLimit"})
record CategoryLimitsWarningResponse(
        @Schema(implementation = MoneySchema.class, description = "Sum of the category limits.")
        Money categoryLimitsTotal,
        @Schema(implementation = MoneySchema.class, description = "The overall limit that the sum exceeds.")
        Money overallLimit) {

    static CategoryLimitsWarningResponse from(CategoryLimitsWarning warning) {
        return new CategoryLimitsWarningResponse(warning.categoryLimitsTotal(), warning.overallLimit());
    }
}
