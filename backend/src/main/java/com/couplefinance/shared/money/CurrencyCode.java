package com.couplefinance.shared.money;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An ISO 4217 currency code (BR-MON-01). Carries no decimals or limits: the number of minor-unit decimals and
 * the per-currency maximum amount are reference data owned by the {@code household} module
 * ({@code household.currency}, database-schema.md §5.1) and reached only through {@link CurrencyDecimals}.
 */
public record CurrencyCode(String value) {

    private static final Pattern ISO_4217 = Pattern.compile("^[A-Z]{3}$");

    public CurrencyCode {
        Objects.requireNonNull(value, "value");
        if (!ISO_4217.matcher(value).matches()) {
            throw new IllegalArgumentException("Currency code must be a 3-letter ISO 4217 code, got: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
