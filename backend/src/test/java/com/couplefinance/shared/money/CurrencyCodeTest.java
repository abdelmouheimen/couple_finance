package com.couplefinance.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyCodeTest {

    @Test
    void BR_MON_01_accepts_a_three_letter_iso_4217_code() {
        assertThat(new CurrencyCode("EUR").value()).isEqualTo("EUR");
    }

    @Test
    void BR_MON_01_currency_cannot_be_constructed_without_a_value() {
        assertThatThrownBy(() -> new CurrencyCode(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eur", "EURO", "EU", "", "12R", "EU ", " EUR"})
    void BR_MON_01_rejects_anything_that_is_not_a_three_letter_uppercase_code(String invalid) {
        assertThatThrownBy(() -> new CurrencyCode(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
