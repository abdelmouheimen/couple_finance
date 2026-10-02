package com.couplefinance.categorization.web;

import java.util.UUID;

import com.couplefinance.categorization.api.RuleScope;
import com.couplefinance.categorization.application.MerchantRuleService.ExplicitRule;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MerchantRule", requiredProperties = {"merchantKey", "categoryId", "sharingType"})
record MerchantRuleResponse(
        @Schema(description = "Normalised merchant key the rule applies to.", example = "carrefour") String merchantKey,
        UUID categoryId,
        RuleScope sharingType) {

    static MerchantRuleResponse from(ExplicitRule rule) {
        return new MerchantRuleResponse(rule.merchantKey(), rule.categoryId(), rule.scope());
    }
}
