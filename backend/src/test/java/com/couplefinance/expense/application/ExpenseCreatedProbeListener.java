package com.couplefinance.expense.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.couplefinance.expense.api.ExpenseCreated;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Test-only consumer of {@link ExpenseCreated}. Spring Modulith registers an event publication only for events
 * that have a transactional listener, so this listener is what lets the tests observe the outbox row and the
 * event payload. Same shape as a real consumer (after commit, own transaction).
 */
@Component
public class ExpenseCreatedProbeListener {

    private final List<ExpenseCreated> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ExpenseCreated event) {
        received.add(event);
    }

    public List<ExpenseCreated> received() {
        return List.copyOf(received);
    }
}
