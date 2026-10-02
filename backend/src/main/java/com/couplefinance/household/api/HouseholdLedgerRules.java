package com.couplefinance.household.api;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;

/**
 * Read-only household facts needed by modules that write financial data (expense). Always keyed by a
 * {@link HouseholdId} taken from the caller's {@link HouseholdContext}, never from a client value.
 */
public interface HouseholdLedgerRules {

    /**
     * @return the currency, time zone and monetary limits of the household
     * @throws IllegalStateException when the household does not exist (callers pass the id of a resolved context)
     */
    LedgerProfile profile(HouseholdId household);

    /** Whether {@code user} is a current (not former) member of the household (BR-EXP-07, BR-HH-02). */
    boolean isActiveMember(HouseholdId household, UserId user);
}
