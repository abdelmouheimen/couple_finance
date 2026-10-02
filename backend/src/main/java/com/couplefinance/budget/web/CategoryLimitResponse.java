package com.couplefinance.budget.web;

import java.util.UUID;

import com.couplefinance.budget.domain.CategoryLimit;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/** One category limit of a budget. */
@Schema(name = "CategoryLimit", requiredProperties = {"categoryId", "limit"})
record CategoryLimitResponse(UUID categoryId, @Schema(implementation = MoneySchema.class) Money limit) {

    static CategoryLimitResponse from(CategoryLimit line) {
        return new CategoryLimitResponse(line.categoryId(), line.limit());
    }
}
