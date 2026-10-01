package com.couplefinance.household.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.household.domain.BudgetPeriodStore;
import com.couplefinance.shared.id.HouseholdId;
import org.junit.jupiter.api.Test;

/** "Today" is computed in the household timezone from the injected Clock (BR-HH-05). */
class BudgetPeriodCalendarServiceTest {

    private static final HouseholdId HOUSEHOLD = new HouseholdId(UUID.randomUUID());
    // 23:30 UTC on 31 March = 01:30 on 1 April in Paris, still 31 March in UTC.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-31T23:30:00Z"), ZoneOffset.UTC);

    @Test
    void BR_HH_05_first_period_contains_today_in_the_household_timezone() {
        InMemoryStore store = new InMemoryStore();
        BudgetPeriodCalendarService service = new BudgetPeriodCalendarService(store, CLOCK);

        service.ensureCalendar(HOUSEHOLD, ZoneId.of("Europe/Paris"), 1);

        assertThat(store.periods.getFirst().start()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void BR_HH_05_utc_household_is_still_on_31_march() {
        InMemoryStore store = new InMemoryStore();
        BudgetPeriodCalendarService service = new BudgetPeriodCalendarService(store, CLOCK);

        service.ensureCalendar(HOUSEHOLD, ZoneOffset.UTC, 1);

        assertThat(store.periods.getFirst().start()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void BR_HH_07_generation_is_idempotent() {
        InMemoryStore store = new InMemoryStore();
        BudgetPeriodCalendarService service = new BudgetPeriodCalendarService(store, CLOCK);

        int first = service.ensureCalendar(HOUSEHOLD, ZoneOffset.UTC, 15);
        List<BudgetPeriod> snapshot = List.copyOf(store.periods);
        int second = service.ensureCalendar(HOUSEHOLD, ZoneOffset.UTC, 15);

        assertThat(first).isPositive();
        assertThat(second).isZero();
        assertThat(store.periods).isEqualTo(snapshot);
    }

    @Test
    void the_calendar_is_extended_when_time_passes_without_altering_stored_periods() {
        InMemoryStore store = new InMemoryStore();
        new BudgetPeriodCalendarService(store, CLOCK).ensureCalendar(HOUSEHOLD, ZoneOffset.UTC, 1);
        List<BudgetPeriod> before = List.copyOf(store.periods);
        Clock sixMonthsLater = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

        int added = new BudgetPeriodCalendarService(store, sixMonthsLater)
                .ensureCalendar(HOUSEHOLD, ZoneOffset.UTC, 1);

        assertThat(added).isEqualTo(6);
        assertThat(store.periods.subList(0, before.size())).isEqualTo(before);
        assertThat(store.periods.getLast().start()).isEqualTo(LocalDate.of(2028, 9, 1));
    }

    private static final class InMemoryStore implements BudgetPeriodStore {

        final List<BudgetPeriod> periods = new ArrayList<>();

        @Override
        public Optional<BudgetPeriod> findLast(HouseholdId householdId) {
            return periods.stream().max(Comparator.comparing(BudgetPeriod::start));
        }

        @Override
        public Optional<BudgetPeriod> findContaining(HouseholdId householdId, LocalDate date) {
            return periods.stream().filter(p -> p.contains(date)).findFirst();
        }

        @Override
        public List<BudgetPeriod> findIntersecting(HouseholdId householdId, LocalDate from, LocalDate to) {
            return periods.stream().filter(p -> p.start().isBefore(to) && p.end().isAfter(from)).toList();
        }

        @Override
        public void insertMissing(HouseholdId householdId, List<BudgetPeriod> toInsert) {
            toInsert.stream().filter(p -> periods.stream().noneMatch(e -> e.start().equals(p.start())))
                    .forEach(periods::add);
        }

        @Override
        public List<CalendarTarget> findActiveCalendarTargets() {
            return List.of();
        }
    }
}
