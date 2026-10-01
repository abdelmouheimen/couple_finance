package com.couplefinance.categorization.infrastructure;

import com.couplefinance.categorization.domain.SortOrderAllocator;
import com.couplefinance.shared.id.HouseholdId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serialises sort-order allocation per household with a PostgreSQL transaction-level advisory lock (released at
 * commit or rollback), then reads the current maximum. The lock is keyed on the household only, so households
 * never block each other. Only the household's own rows are read (system rows have another ordering group).
 */
@Repository
class JdbcSortOrderAllocator implements SortOrderAllocator {

    private final JdbcClient jdbc;

    JdbcSortOrderAllocator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int next(HouseholdId household) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "categorization.category.sort_order:" + household.value())
                .query().singleRow();
        int max = jdbc.sql("SELECT coalesce(max(sort_order), 0) FROM categorization.category WHERE household_id = :household")
                .param("household", household.value())
                .query(Integer.class).single();
        return max + 1;
    }
}
