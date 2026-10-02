package com.couplefinance.budget.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.couplefinance.budget.api.BudgetCreated;
import com.couplefinance.budget.api.BudgetUpdated;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Test-only consumer of {@link BudgetCreated} and {@link BudgetUpdated}: proves they are published on commit. */
@Component
public class BudgetEventsProbeListener {

    private final List<BudgetCreated> created = new CopyOnWriteArrayList<>();
    private final List<BudgetUpdated> updated = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(BudgetCreated event) {
        created.add(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(BudgetUpdated event) {
        updated.add(event);
    }

    public List<BudgetCreated> created() {
        return List.copyOf(created);
    }

    public List<BudgetUpdated> updated() {
        return List.copyOf(updated);
    }
}
