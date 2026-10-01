package com.couplefinance.household.application;

import com.couplefinance.household.domain.Household;

/** Use case behind {@code GET /api/v1/households/me}. */
public interface GetCurrentHousehold {

    /**
     * @throws com.couplefinance.shared.error.ApplicationException {@code HOUSEHOLD_NOT_FOUND} (404)
     */
    Household getCurrentHousehold();
}
