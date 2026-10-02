package com.couplefinance.categorization.web;

import java.util.UUID;

import com.couplefinance.categorization.api.RuleScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code PUT /api/v1/merchant-rules}: "always use this category for this merchant" (BR-CAT-05). */
@Schema(name = "MerchantRuleRequest")
record MerchantRuleRequest(
        @Schema(description = "Merchant as typed; normalised by the server (BR-CAT-07).", example = "Carrefour City")
        @NotBlank @Size(max = 120) String merchant,
        @Schema(description = "A system category or a category of the caller's household, not archived.")
        @NotNull UUID categoryId,
        @Schema(description = "SHARED: household rule. PERSONAL: private rule of the caller, invisible to the "
                + "partner.") @NotNull RuleScope sharingType) {
}
