package com.couplefinance.shared.money;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI description of the JSON shape of {@link Money} (CLAUDE.md section 10.2). Documentation only: the actual
 * (de)serialization is done by {@link MoneySerializer} and {@link MoneyDeserializer}. The amount is a string so
 * that it never goes through a floating-point representation.
 */
@Schema(name = "Money", description = "An exact amount of a currency.")
public record MoneySchema(
        @Schema(description = "Plain decimal string with at most the minor-unit decimals of the currency.",
                example = "12.50", pattern = "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")
        String amount,
        @Schema(description = "ISO 4217 currency code.", example = "EUR", pattern = "^[A-Z]{3}$")
        String currency) {
}
