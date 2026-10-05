package com.couplefinance.budget.web;

import java.util.UUID;

import com.couplefinance.budget.domain.CategoryLimit;
import com.couplefinance.budget.domain.LimitConsumption;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** One category limit of a budget, plus on a read its computed consumption (BR-BUD-03). */
@Schema(name = "CategoryLimit", requiredProperties = {"categoryId", "limit"})
record CategoryLimitResponse(UUID categoryId, @Schema(implementation = MoneySchema.class) Money limit,
        @Schema(description = "Consumption of this category limit, computed on read from the household spending "
                + "of the period restricted to the items of the category (BR-BUD-03). Present on GET; absent in "
                + "the response of a PUT or a copy.")
        @Nullable LimitConsumptionResponse consumption) {

    static CategoryLimitResponse from(CategoryLimit line, @Nullable LimitConsumption consumption) {
        return new CategoryLimitResponse(line.categoryId(), line.limit(),
                consumption == null ? null : LimitConsumptionResponse.from(consumption));
    }
}
