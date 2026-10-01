package com.couplefinance.household.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.shared.id.HouseholdId;

/** Persistence of {@code household.budget_period}. Every method is scoped by household, except the job listing. */
public interface BudgetPeriodStore {

    /** What the extension job needs to extend one household's calendar. */
    record CalendarTarget(HouseholdId householdId, ZoneId timezone, int startDay) {
    }

    Optional<BudgetPeriod> findLast(HouseholdId householdId);

    Optional<BudgetPeriod> findContaining(HouseholdId householdId, LocalDate date);

    /** Periods intersecting {@code [from, to)}, ordered by start. */
    List<BudgetPeriod> findIntersecting(HouseholdId householdId, LocalDate from, LocalDate to);

    /** Inserts the periods; one that is already stored (same start) is left untouched, never altered. */
    void insertMissing(HouseholdId householdId, List<BudgetPeriod> periods);

    /** ACTIVE households (a dissolved one is read-only, BR-HH-10) with the settings the calendar depends on. */
    List<CalendarTarget> findActiveCalendarTargets();
}
