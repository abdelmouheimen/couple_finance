package com.couplefinance.household.domain;

/** Currencies offered to new households (reference data {@code household.currency}). */
public interface SupportedCurrencies {

    boolean isActive(String currencyCode);
}
