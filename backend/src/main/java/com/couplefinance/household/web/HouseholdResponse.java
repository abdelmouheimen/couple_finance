package com.couplefinance.household.web;

import java.time.Instant;
import java.util.UUID;

import com.couplefinance.household.domain.Household;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Household", requiredProperties = {
        "id", "name", "currency", "timezone", "periodStartDay", "status", "createdAt"})
record HouseholdResponse(
        UUID id,
        @Schema(example = "Notre foyer") String name,
        @Schema(example = "EUR") String currency,
        @Schema(example = "Europe/Paris") String timezone,
        @Schema(example = "1") int periodStartDay,
        @Schema(allowableValues = {"ACTIVE", "DISSOLVED"}, example = "ACTIVE") String status,
        Instant createdAt) {

    static HouseholdResponse from(Household household) {
        return new HouseholdResponse(
                household.id().value(),
                household.name(),
                household.currency(),
                household.timezone().getId(),
                household.currentPeriodStartDay(),
                household.status().name(),
                household.createdAt());
    }
}
