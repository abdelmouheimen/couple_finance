package com.couplefinance.household.api;

import java.time.ZoneId;
import java.util.Objects;

import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;

/**
 * What a ledger module needs to know about a household to validate money and dates: its currency (BR-EXP-05), the
 * time zone in which "today" is computed (BR-HH-05), the number of minor-unit decimals of that currency and the
 * per-currency maximum amount of one expense (BR-MON-07).
 *
 * @param maxExpense the maximum amount, in the household currency
 */
public record LedgerProfile(CurrencyCode currency, ZoneId timezone, int decimals, Money maxExpense) {

    public LedgerProfile {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(maxExpense, "maxExpense");
    }
}
