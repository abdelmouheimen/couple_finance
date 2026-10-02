package com.couplefinance.expense.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.expense.api.ExpenseCreated;
import com.couplefinance.expense.api.ExpenseRestored;
import com.couplefinance.expense.api.ExpenseUpdated;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.household.api.TrackingStart;
import com.couplefinance.shared.id.HouseholdId;
import org.junit.jupiter.api.Test;

/** BR-ANA-03 / BR-EXP-07: which expense events may lower the household tracking start. */
class ExpenseTrackingStartListenerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 1, 5);

    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final TrackingStart trackingStart = mock(TrackingStart.class);
    private final ExpenseTrackingStartListener listener = new ExpenseTrackingStartListener(expenses, trackingStart);
    private final UUID expenseId = UUID.randomUUID();
    private final UUID household = UUID.randomUUID();

    private void live(UUID owner) {
        Expense expense = mock(Expense.class);
        when(expense.ownerUserId()).thenReturn(owner);
        when(expense.expenseDate()).thenReturn(DATE);
        when(expenses.findLiveByIdAndHouseholdId(expenseId, household)).thenReturn(Optional.of(expense));
    }

    @Test
    void BR_ANA_03_created_shared_expense_lowers_tracking_start_to_its_date() {
        live(null);
        listener.on(new ExpenseCreated(expenseId, household, null, Instant.now()));
        verify(trackingStart).lowerTo(new HouseholdId(household), DATE);
    }

    @Test
    void BR_ANA_03_updated_and_restored_shared_expenses_lower_it_too() {
        live(null);
        listener.on(new ExpenseUpdated(expenseId, household, null, null, Instant.now()));
        listener.on(new ExpenseRestored(expenseId, household, null, Instant.now()));
        verify(trackingStart, times(2)).lowerTo(new HouseholdId(household), DATE);
    }

    @Test
    void BR_EXP_07_personal_expense_dates_never_reach_the_tracking_start() {
        live(UUID.randomUUID());
        listener.on(new ExpenseCreated(expenseId, household, UUID.randomUUID(), Instant.now()));
        verify(trackingStart, never()).lowerTo(any(), any());
    }

    @Test
    void BR_ANA_03_deleted_or_unknown_expense_is_ignored() {
        when(expenses.findLiveByIdAndHouseholdId(expenseId, household)).thenReturn(Optional.empty());
        listener.on(new ExpenseRestored(expenseId, household, null, Instant.now()));
        verify(trackingStart, never()).lowerTo(any(), any());
    }

    @Test
    void a_failure_never_propagates_to_the_committed_save() {
        when(expenses.findLiveByIdAndHouseholdId(expenseId, household)).thenThrow(new IllegalStateException("db"));
        listener.on(new ExpenseCreated(expenseId, household, null, Instant.now()));
        verify(trackingStart, never()).lowerTo(any(), any());
    }
}
