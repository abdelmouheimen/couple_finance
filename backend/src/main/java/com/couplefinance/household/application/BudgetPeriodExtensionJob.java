package com.couplefinance.household.application;

import com.couplefinance.household.api.SystemHouseholdContext;
import com.couplefinance.household.domain.BudgetPeriodStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily extension of every active household's budget period calendar so it never runs out (database-schema.md
 * §5.5). Each household runs under its own {@link SystemHouseholdContext} and transaction: one failure does not
 * stop the others, and a failed household is retried on the next run. Idempotent, so concurrent instances are
 * harmless.
 */
@Component
@EnableScheduling
public class BudgetPeriodExtensionJob {

    static final String REASON = "BUDGET_PERIOD_EXTENSION";

    private static final Logger log = LoggerFactory.getLogger(BudgetPeriodExtensionJob.class);

    private final BudgetPeriodStore store;
    private final BudgetPeriodCalendarService calendar;

    BudgetPeriodExtensionJob(BudgetPeriodStore store, BudgetPeriodCalendarService calendar) {
        this.store = store;
        this.calendar = calendar;
    }

    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT24H")
    public void extendCalendars() {
        int created = 0;
        for (BudgetPeriodStore.CalendarTarget target : store.findActiveCalendarTargets()) {
            SystemHouseholdContext context = new SystemHouseholdContext(target.householdId(), REASON);
            try {
                created += calendar.ensureCalendar(context.householdId(), target.timezone(), target.startDay());
            } catch (RuntimeException e) {
                log.error("Budget period extension failed for household {}", context.householdId(), e);
            }
        }
        log.info("Budget period extension created {} periods", created);
    }
}
