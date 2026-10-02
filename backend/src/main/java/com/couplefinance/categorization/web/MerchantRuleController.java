package com.couplefinance.categorization.web;

import com.couplefinance.categorization.api.RuleScope;
import com.couplefinance.categorization.application.MerchantRuleService;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rule-based category suggestion and explicit merchant rules of the caller (BR-CAT-04, BR-CAT-05). The household
 * and the user come from the access token; a PERSONAL rule is always the caller's own.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Merchant rules")
class MerchantRuleController {

    private final MerchantRuleService rules;

    MerchantRuleController(MerchantRuleService rules) {
        this.rules = rules;
    }

    @GetMapping("/category-suggestions")
    @Operation(operationId = "suggestCategory", summary = "Suggest a category for a merchant",
            description = "Rule-based suggestion (BR-CAT-04): for PERSONAL the caller's private rule, then the "
                    + "household rule; for SHARED the household rule only; otherwise the system category Other. "
                    + "Rules pointing to an archived category are skipped. The AI step is not available yet. "
                    + "Readable by archive readers of a dissolved household.")
    @ApiResponse(responseCode = "200", description = "The suggestion and its source")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    CategorySuggestionResponse suggest(
            @Parameter(description = "Merchant as typed (at most 120 characters).") @RequestParam String merchant,
            @Parameter(description = "Sharing type of the expense being entered.")
            @RequestParam RuleScope sharingType) {
        return CategorySuggestionResponse.from(rules.suggest(merchant, sharingType));
    }

    @PutMapping("/merchant-rules")
    @Operation(operationId = "setMerchantRule", summary = "Always use this category for this merchant",
            description = "Creates or replaces the rule of the caller's scope for the normalised merchant "
                    + "(BR-CAT-05): a household rule for SHARED, a private user rule for PERSONAL. Idempotent.")
    @ApiResponse(responseCode = "200", description = "The rule now in force")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST or CATEGORY_ARCHIVED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or RESOURCE_NOT_FOUND (unknown category "
            + "or category of another household)", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    MerchantRuleResponse setRule(@Valid @RequestBody MerchantRuleRequest request) {
        return MerchantRuleResponse.from(
                rules.setRule(request.merchant(), request.categoryId(), request.sharingType()));
    }
}
