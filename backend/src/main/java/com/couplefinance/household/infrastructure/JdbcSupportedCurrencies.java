package com.couplefinance.household.infrastructure;

import com.couplefinance.household.domain.SupportedCurrencies;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the {@code household.currency} reference data seeded by Liquibase. */
@Repository
class JdbcSupportedCurrencies implements SupportedCurrencies {

    private final JdbcClient jdbc;

    JdbcSupportedCurrencies(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActive(String currencyCode) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM household.currency WHERE code = :code AND active)")
                .param("code", currencyCode)
                .query(Boolean.class)
                .single();
    }
}
