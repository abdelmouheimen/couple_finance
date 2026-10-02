package com.couplefinance.expense.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.couplefinance.expense.domain.SharingType;
import com.couplefinance.expense.domain.SpendingAggregates;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the spending formula (BR-SCP-01/02). The text is built only from fixed fragments; every value is a
 * bound parameter. The household predicate, the view predicate (SHARED: {@code owner_user_id IS NULL}, PERSONAL:
 * {@code owner_user_id = :userId}, BR-EXP-07), the exclusion of deleted rows and the kind filter are always
 * present. Only {@code EXPENSE} adds and {@code REFUND} subtracts, so TRANSFER (and any kind added later) is
 * excluded by construction. The period is half-open on the expense date, so a refund counts on its own date.
 */
@Repository
class JdbcSpendingAggregates implements SpendingAggregates {

    private static final String FILTER = " WHERE e.household_id = :householdId AND e.deleted_at IS NULL"
            + " AND e.kind IN ('EXPENSE', 'REFUND') AND e.expense_date >= :start AND e.expense_date < :end"
            + " AND (CASE WHEN :personal THEN e.owner_user_id = :userId ELSE e.owner_user_id IS NULL END)";
    private static final String SIGN = "CASE e.kind WHEN 'EXPENSE' THEN 1 ELSE -1 END";

    private final JdbcClient jdbc;

    JdbcSpendingAggregates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long total(Criteria criteria) {
        return bind("SELECT coalesce(sum(e.amount_minor * (" + SIGN + ")), 0)::bigint AS net"
                + " FROM expense.expense e" + FILTER, criteria).query(Long.class).single();
    }

    @Override
    public List<Entry<UUID>> byCategory(Criteria criteria) {
        return bind("SELECT i.category_id AS k, sum(i.amount_minor * (" + SIGN + "))::bigint AS net"
                + " FROM expense.expense e JOIN expense.expense_item i"
                + " ON i.expense_id = e.id AND i.household_id = e.household_id" + FILTER
                + " GROUP BY i.category_id ORDER BY i.category_id", criteria)
                .query((rs, row) -> new Entry<>(rs.getObject("k", UUID.class), rs.getLong("net"))).list();
    }

    @Override
    public List<Entry<UUID>> byPayer(Criteria criteria) {
        return bind("SELECT e.paid_by_user_id AS k, sum(e.amount_minor * (" + SIGN + "))::bigint AS net"
                + " FROM expense.expense e" + FILTER + " GROUP BY e.paid_by_user_id ORDER BY e.paid_by_user_id",
                criteria).query((rs, row) -> new Entry<>(rs.getObject("k", UUID.class), rs.getLong("net"))).list();
    }

    @Override
    public List<Entry<LocalDate>> byDay(Criteria criteria) {
        return bind("SELECT e.expense_date AS k, sum(e.amount_minor * (" + SIGN + "))::bigint AS net"
                + " FROM expense.expense e" + FILTER + " GROUP BY e.expense_date ORDER BY e.expense_date",
                criteria).query((rs, row) -> new Entry<>(rs.getObject("k", LocalDate.class), rs.getLong("net")))
                .list();
    }

    private JdbcClient.StatementSpec bind(String sql, Criteria c) {
        return jdbc.sql(sql).param("householdId", c.householdId()).param("personal", c.scope() == SharingType.PERSONAL)
                .param("userId", c.userId()).param("start", c.start()).param("end", c.end());
    }
}
