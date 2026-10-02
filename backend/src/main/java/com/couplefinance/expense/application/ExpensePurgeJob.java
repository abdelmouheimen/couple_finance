package com.couplefinance.expense.application;

import java.time.Clock;
import java.time.Instant;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily retention purge of the expense module (BR-EXP-11, BR-DAT, security.md section 4.4; system actor
 * {@code SYSTEM:expense-purge}, which writes no audit row since it deletes the audit trail). Bounded batches, one transaction each, at most {@link #MAX_BATCHES} per run per
 * kind. Idempotent, and safe on several instances: batches lock their rows with SKIP LOCKED, so no row is
 * processed twice (equivalent to a ShedLock singleton lock, without a new dependency). Logs hold counts only.
 */
@Component
@EnableScheduling
public class ExpensePurgeJob {

    static final int BATCH_SIZE = 500;
    static final int MAX_BATCHES = 200;

    private static final Logger log = LoggerFactory.getLogger(ExpensePurgeJob.class);

    private final ExpensePurgeService service;
    private final Clock clock;

    ExpensePurgeJob(ExpensePurgeService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT20M", fixedDelayString = "PT24H")
    public void run() {
        run(clock.instant());
    }

    /** Runs the purge as of {@code now}; returns {@code {expensesPurged, auditRowsPurged}}. */
    public int[] run(Instant now) {
        int expenses = drain(() -> service.purgeDeletedExpenses(now, BATCH_SIZE), "expense");
        int audit = drain(() -> service.purgeExpiredAudit(now, BATCH_SIZE), "audit");
        log.info("Expense retention purge removed {} expenses and {} expired audit rows", expenses, audit);
        return new int[] {expenses, audit};
    }

    private static int drain(IntSupplier batch, String kind) {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES; i++) {
                int removed = batch.getAsInt();
                total += removed;
                if (removed == 0) { // a refund purged in this batch frees its original for the next one
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("Expense retention purge of {} failed after {} rows: {}", kind, total, e.getClass().getName());
        }
        return total;
    }
}
