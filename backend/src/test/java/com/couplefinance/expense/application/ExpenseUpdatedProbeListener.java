package com.couplefinance.expense.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.couplefinance.expense.api.ExpenseUpdated;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Test-only consumer of {@link ExpenseUpdated}; same purpose as {@link ExpenseCreatedProbeListener}. */
@Component
public class ExpenseUpdatedProbeListener {

    private final List<ExpenseUpdated> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ExpenseUpdated event) {
        received.add(event);
    }

    public List<ExpenseUpdated> received() {
        return List.copyOf(received);
    }
}
