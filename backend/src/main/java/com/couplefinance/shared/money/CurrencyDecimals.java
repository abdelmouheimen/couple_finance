package com.couplefinance.shared.money;

/**
 * Resolves the number of minor-unit decimals of a currency (EUR → 2, JPY → 0, TND → 3), needed to parse a decimal
 * string into a {@link Money} or to convert minor units back to a decimal amount (BR-MON-02, BR-MON-03).
 *
 * <p>This is a port: the {@code shared} kernel defines the contract but never reads the {@code household} schema
 * itself. It is implemented by the {@code household} module, backed by the {@code household.currency} reference
 * data it owns (database-schema.md §5.1), and injected as a Spring bean into any module that needs it.
 */
public interface CurrencyDecimals {

    /**
     * @param currency a currency code present in the reference data
     * @return the number of minor-unit decimals for {@code currency} (0-4)
     * @throws RuntimeException (implementation-specific) if {@code currency} is not known reference data — callers
     *         are expected to only pass currencies already validated against that reference data
     */
    int decimalsOf(CurrencyCode currency);
}
