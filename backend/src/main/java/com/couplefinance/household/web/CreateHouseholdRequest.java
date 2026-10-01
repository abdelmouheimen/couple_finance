package com.couplefinance.household.web;

import com.couplefinance.household.application.CreateHouseholdCommand;
import com.couplefinance.household.domain.Household;
import com.couplefinance.household.domain.PeriodRule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/households}. Intentionally has no user or owner field: the creator is the
 * authenticated user, and unknown JSON properties are rejected.
 */
@Schema(name = "CreateHouseholdRequest")
record CreateHouseholdRequest(
        @Schema(description = "Household name, trimmed.", example = "Notre foyer")
        @NotBlank @Size(min = 1, max = Household.NAME_MAX_LENGTH)
        String name,

        @Schema(description = "ISO 4217 currency code. Defaults to EUR.", example = "EUR",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code such as EUR")
        String currency,

        @Schema(description = "IANA time-zone id used to compute \"today\". Defaults to Europe/Paris.",
                example = "Europe/Paris", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(min = 1, max = 64)
        String timezone,

        @Schema(description = "Day of month on which budget periods start (1-28). Defaults to 1.", example = "1",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Min(PeriodRule.MIN_START_DAY) @Max(PeriodRule.MAX_START_DAY)
        Integer periodStartDay) {

    CreateHouseholdCommand toCommand() {
        return new CreateHouseholdCommand(name, currency, timezone, periodStartDay);
    }
}
