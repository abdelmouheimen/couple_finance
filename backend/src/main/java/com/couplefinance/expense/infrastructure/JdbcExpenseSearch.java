package com.couplefinance.expense.infrastructure;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.expense.domain.ExpenseSearch;
import com.couplefinance.expense.domain.SharingType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL for the history: dynamic filters, keyset pagination and the signed aggregate. The SQL text is built
 * only from fixed fragments; every value is a bound parameter. The household predicate and the view predicate
 * (SHARED: {@code owner_user_id IS NULL}, PERSONAL: {@code owner_user_id = :userId}) are always present (BR-EXP-07).
 */
@Repository
class JdbcExpenseSearch implements ExpenseSearch {

    private final JdbcClient jdbc;

    JdbcExpenseSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Position> page(Criteria criteria, Optional<Position> after, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = where(criteria, params);
        after.ifPresent(position -> {
            where.append(" AND (e.expense_date, e.id) < (:afterDate, :afterId)");
            params.put("afterDate", position.date());
            params.put("afterId", position.id());
        });
        params.put("limit", limit);
        return jdbc.sql("SELECT e.expense_date, e.id FROM expense.expense e WHERE " + where
                        + " ORDER BY e.expense_date DESC, e.id DESC LIMIT :limit")
                .params(params)
                .query((rs, row) -> new Position(rs.getObject("expense_date", LocalDate.class),
                        rs.getObject("id", UUID.class)))
                .list();
    }

    @Override
    public Totals totals(Criteria criteria) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = where(criteria, params);
        // With a category filter the amount of an expense is the sum of its items of that category.
        String amount = criteria.categoryId() == null ? "e.amount_minor"
                : "(SELECT coalesce(sum(i.amount_minor), 0) FROM expense.expense_item i "
                        + "WHERE i.expense_id = e.id AND i.household_id = e.household_id "
                        + "AND i.category_id = :categoryId)";
        return jdbc.sql("SELECT count(*) AS cnt, coalesce(sum(CASE e.kind WHEN 'EXPENSE' THEN " + amount
                        + " WHEN 'REFUND' THEN -" + amount + " ELSE 0 END), 0) AS net "
                        + "FROM expense.expense e WHERE " + where)
                .params(params)
                .query((rs, row) -> new Totals(rs.getLong("cnt"), rs.getLong("net")))
                .single();
    }

    private static StringBuilder where(Criteria c, Map<String, Object> params) {
        // TRANSFER is not a spending (BR-SCP-01) and is not yet representable in the domain (ExpenseKind): it is
        // neither listed nor counted nor totalled until the transfer Issue introduces it.
        StringBuilder where = new StringBuilder("e.household_id = :householdId AND e.deleted_at IS NULL "
                + "AND e.kind IN ('EXPENSE', 'REFUND')");
        params.put("householdId", c.householdId());
        if (c.scope() == SharingType.PERSONAL) {
            where.append(" AND e.owner_user_id = :userId");
            params.put("userId", c.userId());
        } else {
            where.append(" AND e.owner_user_id IS NULL");
        }
        if (c.from() != null) {
            where.append(" AND e.expense_date >= :from");
            params.put("from", c.from());
        }
        if (c.to() != null) {
            where.append(" AND e.expense_date <= :to");
            params.put("to", c.to());
        }
        if (c.categoryId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM expense.expense_item f WHERE f.expense_id = e.id "
                    + "AND f.household_id = e.household_id AND f.category_id = :categoryId)");
            params.put("categoryId", c.categoryId());
        }
        if (c.paidByUserId() != null) {
            where.append(" AND e.paid_by_user_id = :paidBy");
            params.put("paidBy", c.paidByUserId());
        }
        if (c.kind() != null) {
            where.append(" AND e.kind = :kind");
            params.put("kind", c.kind().name());
        }
        if (c.hasReceipt() != null) {
            where.append(c.hasReceipt() ? " AND e.receipt_id IS NOT NULL" : " AND e.receipt_id IS NULL");
        }
        if (c.text() != null) {
            where.append(" AND (e.merchant_display ILIKE :text ESCAPE '\\' OR e.note ILIKE :text ESCAPE '\\')");
            params.put("text", "%" + c.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return where;
    }
}
