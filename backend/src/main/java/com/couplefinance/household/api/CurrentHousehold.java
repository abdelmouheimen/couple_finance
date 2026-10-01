package com.couplefinance.household.api;

import java.util.Optional;

/** Resolves the {@link HouseholdContext} of the authenticated user of the current request. */
public interface CurrentHousehold {

    /**
     * The caller's household context.
     *
     * @throws com.couplefinance.shared.error.ApplicationException {@code HOUSEHOLD_NOT_FOUND} (404) when the
     *         caller has neither an active household nor a valid archive access
     */
    HouseholdContext currentHousehold();

    /** Same as {@link #currentHousehold()}, empty instead of failing when the caller has no household. */
    Optional<HouseholdContext> findCurrentHousehold();
}
