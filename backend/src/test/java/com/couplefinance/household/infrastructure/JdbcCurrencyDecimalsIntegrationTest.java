package com.couplefinance.household.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.CurrencyDecimals;
import com.couplefinance.shared.money.Money;
import com.couplefinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CurrencyDecimals} read from the real {@code household.currency} reference data (SHARED-001): the
 * {@code shared} kernel never queries this table itself, only through this port.
 */
@IntegrationTest
class JdbcCurrencyDecimalsIntegrationTest {

    @Autowired
    CurrencyDecimals currencyDecimals;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void reads_minor_units_for_a_zero_decimal_currency() {
        assertThat(currencyDecimals.decimalsOf(new CurrencyCode("JPY"))).isZero();
    }

    @Test
    void reads_minor_units_for_a_two_decimal_currency() {
        assertThat(currencyDecimals.decimalsOf(new CurrencyCode("EUR"))).isEqualTo(2);
    }

    @Test
    void reads_minor_units_for_a_three_decimal_currency() {
        assertThat(currencyDecimals.decimalsOf(new CurrencyCode("TND"))).isEqualTo(3);
    }

    @Test
    void rejects_an_unknown_currency() {
        assertThatThrownBy(() -> currencyDecimals.decimalsOf(new CurrencyCode("XXX")))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void money_json_module_is_wired_with_the_real_household_backed_port() {
        CurrencyCode eur = new CurrencyCode("EUR");
        Money money = Money.parse("12.50", eur, currencyDecimals.decimalsOf(eur));

        String json = jsonMapper.writeValueAsString(money);
        Money reparsed = jsonMapper.readValue(json, Money.class);

        assertThat(json).isEqualTo("{\"amount\":\"12.50\",\"currency\":\"EUR\"}");
        assertThat(reparsed).isEqualTo(money);
    }
}
