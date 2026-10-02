package com.couplefinance.expense.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.domain.ExpensePurgeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC implementation of {@link ExpensePurgeRepository}; deliberately unscoped by household (purge job only).
 */
@Repository
class JdbcExpensePurgeRepository implements ExpensePurgeRepository {

    private final JdbcClient jdbc;

    JdbcExpensePurgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PurgedExpense> purgeDeletedBefore(Instant cutoff, int limit) {
        // doomed: the expense rows are locked first (SKIP LOCKED: a concurrent instance takes other rows), then
        // their audit rows and the expenses are deleted in one statement; items go by ON DELETE CASCADE.
        // An expense referenced by a refund row is left for a later batch (fk_expense_refund_of is RESTRICT).
        return jdbc.sql("""
                        WITH doomed AS (
                            SELECT e.id, e.household_id, e.owner_user_id, e.receipt_id
                            FROM expense.expense e
                            WHERE e.deleted_at < :cutoff
                              AND NOT EXISTS (SELECT 1 FROM expense.expense r
                                              WHERE r.household_id = e.household_id AND r.refund_of_expense_id = e.id)
                            ORDER BY e.deleted_at, e.id
                            LIMIT :limit
                            FOR UPDATE OF e SKIP LOCKED
                        ),
                        audit AS (
                            DELETE FROM expense.audit_event a USING doomed d
                            WHERE a.household_id = d.household_id AND a.entity_id = d.id AND a.entity_type = 'EXPENSE'
                        )
                        DELETE FROM expense.expense x USING doomed d
                        WHERE x.id = d.id
                        RETURNING d.id, d.household_id, d.owner_user_id, d.receipt_id
                        """)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("limit", limit)
                .query((rs, row) -> new PurgedExpense(rs.getObject("id", UUID.class),
                        rs.getObject("household_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("receipt_id", UUID.class)))
                .list();
    }

    @Override
    public int purgeAuditBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                        DELETE FROM expense.audit_event
                        WHERE id IN (SELECT id FROM expense.audit_event
                                     WHERE occurred_at < :cutoff
                                     ORDER BY occurred_at, id
                                     LIMIT :limit
                                     FOR UPDATE SKIP LOCKED)
                        """)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("limit", limit)
                .update();
    }
}
