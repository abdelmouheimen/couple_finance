package com.couplefinance.expense.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.couplefinance.expense.api.ExpensePurged;
import com.couplefinance.expense.domain.ExpensePurgeRepository;
import com.couplefinance.expense.domain.ExpensePurgeRepository.PurgedExpense;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transaction per bounded purge batch (BR-EXP-11, BR-DAT). Each batch deletes the rows and publishes
 * {@code ExpensePurged} for every purged expense in the same transaction. No external call is made inside it.
 */
@Service
public class ExpensePurgeService {

    /** BR-EXP-11: a deleted expense can be restored within 90 days, then it is purged. */
    static final int DELETED_RETENTION_DAYS = 90;
    /** BR-DAT: general audit retention. */
    static final int AUDIT_RETENTION_MONTHS = 24;

    private final ExpensePurgeRepository purges;
    private final ApplicationEventPublisher events;

    ExpensePurgeService(ExpensePurgeRepository purges, ApplicationEventPublisher events) {
        this.purges = purges;
        this.events = events;
    }

    /** Purges up to {@code batchSize} expenses deleted strictly more than 90 days before {@code now}. */
    @Transactional
    public int purgeDeletedExpenses(Instant now, int batchSize) {
        Instant cutoff = now.minus(DELETED_RETENTION_DAYS, ChronoUnit.DAYS);
        List<PurgedExpense> purged = purges.purgeDeletedBefore(cutoff, batchSize);
        for (PurgedExpense expense : purged) {
            events.publishEvent(new ExpensePurged(expense.expenseId(), expense.householdId(),
                    expense.ownerUserId(), expense.receiptId(), now));
        }
        return purged.size();
    }

    /** Purges up to {@code batchSize} audit rows older than 24 months before {@code now}. */
    @Transactional
    public int purgeExpiredAudit(Instant now, int batchSize) {
        Instant cutoff = now.atOffset(ZoneOffset.UTC).minusMonths(AUDIT_RETENTION_MONTHS).toInstant();
        return purges.purgeAuditBefore(cutoff, batchSize);
    }
}
