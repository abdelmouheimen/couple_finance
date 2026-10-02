package com.couplefinance.household.api;

import java.time.LocalDate;

import com.couplefinance.shared.id.HouseholdId;

/**
 * The household tracking start (BR-ANA-03): the earliest of the household creation date (in the household
 * timezone) and the earliest SHARED expense date. Analytics restricts the 3-period average to periods on or after it.
 *
 * <p>PERSONAL expense dates never feed it (BR-EXP-07): the value is visible to both partners and must not let one
 * infer the existence or date of the other's personal expense. Always keyed by a {@link HouseholdId} taken from a
 * resolved household context, never from a client value.
 */
public interface TrackingStart {

    /** @throws IllegalStateException when the household does not exist (callers pass the id of a resolved context) */
    LocalDate of(HouseholdId household);

    /**
     * Lowers the tracking start to {@code candidate} when it is earlier than the stored value; never raises it.
     * Idempotent and order-independent (the stored value is the minimum of every candidate seen).
     *
     * @return whether the stored value changed
     */
    boolean lowerTo(HouseholdId household, LocalDate candidate);
}
