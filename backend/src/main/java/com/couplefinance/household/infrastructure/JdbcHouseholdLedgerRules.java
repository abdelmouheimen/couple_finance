package com.couplefinance.household.infrastructure;

import java.time.ZoneId;

import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Implements {@link HouseholdLedgerRules} over the household module's own tables. */
@Repository
class JdbcHouseholdLedgerRules implements HouseholdLedgerRules {

    private final JdbcClient jdbc;

    JdbcHouseholdLedgerRules(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LedgerProfile profile(HouseholdId household) {
        return jdbc.sql("""
                        SELECT h.currency, h.timezone, c.minor_units, c.max_expense_minor
                        FROM household.household h
                        JOIN household.currency c ON c.code = h.currency
                        WHERE h.id = :household
                        """)
                .param("household", household.value())
                .query((rs, row) -> {
                    CurrencyCode currency = new CurrencyCode(rs.getString("currency").strip());
                    int decimals = rs.getInt("minor_units");
                    return new LedgerProfile(currency, ZoneId.of(rs.getString("timezone")), decimals,
                            Money.ofMinor(rs.getLong("max_expense_minor"), currency, decimals));
                })
                .optional()
                .orElseThrow(() -> new IllegalStateException("household not found"));
    }

    @Override
    public boolean isActiveMember(HouseholdId household, UserId user) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM household.household_member
                                       WHERE household_id = :household AND user_id = :user AND left_at IS NULL)
                        """)
                .param("household", household.value())
                .param("user", user.value())
                .query(Boolean.class)
                .single();
    }
}
