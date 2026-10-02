package com.couplefinance.expense.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.couplefinance.expense.api.ExpensePurged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Test-only consumer of {@link ExpensePurged}; same purpose as {@link ExpenseCreatedProbeListener}. */
@Component
public class ExpensePurgedProbeListener {

    private final List<ExpensePurged> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ExpensePurged event) {
        received.add(event);
    }

    public List<ExpensePurged> received() {
        return List.copyOf(received);
    }
}
