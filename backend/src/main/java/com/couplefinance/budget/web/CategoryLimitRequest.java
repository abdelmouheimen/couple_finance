package com.couplefinance.budget.web;

import java.util.UUID;

import com.couplefinance.budget.domain.CategoryLimit;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** One category limit of a budget in a request (BR-BUD-01). */
@Schema(name = "CategoryLimitRequest", requiredProperties = {"categoryId", "limit"})
record CategoryLimitRequest(
        @Schema(description = "An active system or household category. A category of another household is a 404.")
        @NotNull UUID categoryId,
        @Schema(implementation = MoneySchema.class, description = "Limit for the period, in the household "
                + "currency, strictly positive.")
        @NotNull Money limit) {

    CategoryLimit toDomain() {
        return new CategoryLimit(categoryId, limit);
    }
}
