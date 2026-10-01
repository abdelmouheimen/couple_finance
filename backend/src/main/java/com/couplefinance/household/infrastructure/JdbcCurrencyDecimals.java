package com.couplefinance.household.infrastructure;

import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.CurrencyDecimals;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Implements the {@code shared} kernel's {@link CurrencyDecimals} port, backed by the {@code household.currency}
 * reference data this module owns (database-schema.md §5.1). The {@code shared} kernel never queries this table
 * directly.
 *
 * <p>An unknown currency surfaces as a {@link org.springframework.dao.EmptyResultDataAccessException} (a
 * {@code @Repository} bean has Spring's standard data-access exception translation applied) — callers are
 * expected to only pass currencies already validated against the reference data.
 */
@Repository
class JdbcCurrencyDecimals implements CurrencyDecimals {

    private final JdbcClient jdbc;

    JdbcCurrencyDecimals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int decimalsOf(CurrencyCode currency) {
        return jdbc.sql("SELECT minor_units FROM household.currency WHERE code = :code")
                .param("code", currency.value())
                .query(Integer.class)
                .single();
    }
}
