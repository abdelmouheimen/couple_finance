package com.couplefinance.identity.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.couplefinance.identity.api.AiConsentPurpose;
import com.couplefinance.identity.application.AiConsentService;
import com.couplefinance.shared.error.ProblemSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's AI consents (ADR-005, BR-AI-05): per purpose, explicit, withdrawable. */
@RestController
@RequestMapping("/api/v1/me/ai-consents")
@Tag(name = "Identity")
class AiConsentController {

    private static final String PROBLEM = "application/problem+json";

    private final AiConsentService consents;

    AiConsentController(AiConsentService consents) {
        this.consents = consents;
    }

    @Schema(name = "AiConsent", requiredProperties = {"purpose", "granted"})
    record ConsentResponse(
            AiConsentPurpose purpose,
            @Schema(description = "Whether the caller currently consents to this purpose (never inferred).")
            boolean granted,
            @Schema(description = "Wording version of the last decision.",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String consentTextVersion,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable Instant decidedAt) {

        static ConsentResponse of(AiConsentPurpose purpose, AiConsentService.ConsentState state) {
            return new ConsentResponse(purpose, state.granted(), state.textVersion().orElse(null),
                    state.decidedAt().orElse(null));
        }
    }

    @Schema(name = "AiConsentList", requiredProperties = {"items"})
    record ConsentListResponse(List<ConsentResponse> items) {
    }

    @Schema(name = "AiConsentDecision", requiredProperties = {"granted", "consentTextVersion"})
    record DecisionRequest(
            @NotNull @Schema(description = "true to grant, false to withdraw.") Boolean granted,
            @NotBlank @Size(max = AiConsentService.TEXT_VERSION_MAX_LENGTH)
            @Schema(description = "Version of the consent wording the user was shown, e.g. receipt-analysis-v1.")
            String consentTextVersion) {
    }

    @GetMapping
    @Operation(operationId = "listAiConsents", summary = "Read the caller's AI consents",
            description = "One entry per purpose (RECEIPT_ANALYSIS, INSIGHTS_ASSISTANT, QUALITY_DATASET). A purpose "
                    + "never decided is not granted.")
    @ApiResponse(responseCode = "200", description = "The caller's consents")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    ConsentListResponse list() {
        Map<AiConsentPurpose, AiConsentService.ConsentState> states = consents.current();
        return new ConsentListResponse(states.entrySet().stream()
                .map(e -> ConsentResponse.of(e.getKey(), e.getValue())).toList());
    }

    @PutMapping("/{purpose}")
    @Operation(operationId = "decideAiConsent", summary = "Grant or withdraw an AI consent",
            description = "Records the caller's decision with the wording version shown (BR-AI-05). Withdrawing is "
                    + "effective immediately, including for queued receipt analyses.")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or AI_CONSENT_TEXT_VERSION_INVALID",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    ConsentResponse decide(@PathVariable AiConsentPurpose purpose, @Valid @RequestBody DecisionRequest request) {
        return ConsentResponse.of(purpose, consents.decide(purpose, request.granted(), request.consentTextVersion()));
    }
}
