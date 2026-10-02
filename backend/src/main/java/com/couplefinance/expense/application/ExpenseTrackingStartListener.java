package com.couplefinance.expense.application;

import com.couplefinance.expense.api.ExpenseCreated;
import com.couplefinance.expense.api.ExpenseRestored;
import com.couplefinance.expense.api.ExpenseUpdated;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.household.api.TrackingStart;
import com.couplefinance.shared.id.HouseholdId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BR-ANA-03: lowers the household tracking start when a SHARED expense dated before it is created, edited or
 * restored. It lives in the expense module (which already depends on {@code household.api}) so that the household
 * module needs no dependency on expense.
 *
 * <p>The expense is re-read (household-scoped, live only) instead of trusting the payload, so a re-delivery, an
 * out-of-order delivery or an event for a since-deleted expense is harmless: lowering is idempotent and never raises.
 * PERSONAL expenses are ignored (BR-EXP-07): their dates must not be inferable by the partner through the
 * household-wide tracking start. A failure never fails the committed save: it is logged (ids only) and skipped.
 */
@Component
class ExpenseTrackingStartListener {

    private static final Logger LOG = LoggerFactory.getLogger(ExpenseTrackingStartListener.class);

    private final ExpenseRepository expenses;
    private final TrackingStart trackingStart;

    ExpenseTrackingStartListener(ExpenseRepository expenses, TrackingStart trackingStart) {
        this.expenses = expenses;
        this.trackingStart = trackingStart;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ExpenseCreated event) {
        apply(event.expenseId(), event.householdId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ExpenseUpdated event) {
        apply(event.expenseId(), event.householdId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ExpenseRestored event) {
        apply(event.expenseId(), event.householdId());
    }

    private void apply(UUID expenseId, UUID householdId) {
        try {
            expenses.findLiveByIdAndHouseholdId(expenseId, householdId)
                    .filter(expense -> expense.ownerUserId() == null)
                    .ifPresent(expense -> trackingStart.lowerTo(new HouseholdId(householdId), expense.expenseDate()));
        } catch (RuntimeException e) {
            LOG.warn("Tracking start update failed for expense {}: {}", expenseId, e.getClass().getSimpleName());
        }
    }
}
