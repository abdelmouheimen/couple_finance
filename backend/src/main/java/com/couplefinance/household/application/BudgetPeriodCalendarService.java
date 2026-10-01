package com.couplefinance.household.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.household.api.BudgetPeriods;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.domain.BudgetPeriodCalendar;
import com.couplefinance.household.domain.BudgetPeriodStore;
import com.couplefinance.shared.id.HouseholdId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialises and reads the budget period calendar (BR-HH-05, BR-HH-07). Generation only appends periods after
 * the last stored one (or starts with the one containing "today" in the household timezone); stored periods are
 * never updated, so re-running is a no-op.
 */
@Service
public class BudgetPeriodCalendarService implements BudgetPeriods {

    private final BudgetPeriodStore store;
    private final Clock clock;

    BudgetPeriodCalendarService(BudgetPeriodStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Ensures the calendar covers the current period and 24 months ahead. Joins the caller's transaction (the
     * household creation) or opens its own (the extension job). Concurrent runs are safe: inserts are
     * {@code ON CONFLICT DO NOTHING} on the primary key.
     *
     * @return the number of periods that were missing
     */
    @Transactional
    public int ensureCalendar(HouseholdId householdId, ZoneId timezone, int startDay) {
        LocalDate today = LocalDate.now(clock.withZone(timezone));
        List<BudgetPeriod> missing =
                BudgetPeriodCalendar.missingPeriods(startDay, store.findLast(householdId), today);
        store.insertMissing(householdId, missing);
        return missing.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BudgetPeriod> findPeriodContaining(HouseholdContext context, LocalDate date) {
        return store.findContaining(context.householdId(), date);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BudgetPeriod> listPeriods(HouseholdContext context, LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("'to' must be after 'from'.");
        }
        return store.findIntersecting(context.householdId(), from, to);
    }
}
