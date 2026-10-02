package com.couplefinance.expense.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.couplefinance.expense.api.ExpenseDeleted;
import com.couplefinance.expense.api.ExpenseRestored;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Test-only consumer of {@link ExpenseDeleted} and {@link ExpenseRestored}; same purpose as the other probes. */
@Component
public class ExpenseDeletedProbeListener {

    private final List<ExpenseDeleted> deleted = new CopyOnWriteArrayList<>();
    private final List<ExpenseRestored> restored = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ExpenseDeleted event) {
        deleted.add(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ExpenseRestored event) {
        restored.add(event);
    }

    public List<ExpenseDeleted> deleted() {
        return List.copyOf(deleted);
    }

    public List<ExpenseRestored> restored() {
        return List.copyOf(restored);
    }
}
