package com.couplefinance.budget.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;

/** Issue #28: BR-BUD-03/05/06 status part, BR-MON-09. Amounts in EUR minor units (2 decimals). */
class LimitConsumptionTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");

    private static LimitConsumption of(long limitMinor, long consumedMinor) {
        return LimitConsumption.of(Money.ofMinor(limitMinor, EUR, 2), Money.ofMinor(consumedMinor, EUR, 2));
    }

    @Test
    void BR_BUD_06_status_thresholds() {
        // limit 1000.00: 79.9% / 80.0% / 100.0% / 100.1%
        assertThat(of(100_000, 79_900).status()).isEqualTo(BudgetStatus.ON_TRACK);
        assertThat(of(100_000, 80_000).status()).isEqualTo(BudgetStatus.WARNING);
        assertThat(of(100_000, 100_000).status()).isEqualTo(BudgetStatus.WARNING);
        assertThat(of(100_000, 100_100).status()).isEqualTo(BudgetStatus.EXCEEDED);
        // exact boundaries on minor units: 79.99 of 100.00 is below 80%, one cent over the limit is exceeded
        assertThat(of(10_000, 7_999).status()).isEqualTo(BudgetStatus.ON_TRACK);
        assertThat(of(10_000, 8_000).status()).isEqualTo(BudgetStatus.WARNING);
        assertThat(of(10_000, 10_001).status()).isEqualTo(BudgetStatus.EXCEEDED);
        assertThat(of(3, 2).status()).isEqualTo(BudgetStatus.ON_TRACK); // 66.7%
        assertThat(of(5, 4).status()).isEqualTo(BudgetStatus.WARNING); // exactly 80%
    }

    @Test
    void BR_BUD_06_a_negative_or_zero_consumption_is_on_track() {
        assertThat(of(100_000, 0).status()).isEqualTo(BudgetStatus.ON_TRACK);
        assertThat(of(100_000, -2_500).status()).isEqualTo(BudgetStatus.ON_TRACK);
    }

    @Test
    void BR_BUD_05_remaining_can_be_negative() {
        assertThat(of(100_000, 120_050).remaining().toDecimalString()).isEqualTo("-200.50");
        assertThat(of(100_000, 40_000).remaining().toDecimalString()).isEqualTo("600.00");
        assertThat(of(100_000, -1_000).remaining().toDecimalString()).isEqualTo("1010.00");
    }

    @Test
    void BR_MON_09_percentage_half_even() {
        assertThat(of(100_000, 82_500).percentage()).hasToString("82.5");
        assertThat(of(3, 1).percentage()).hasToString("33.3");
        assertThat(of(3, 2).percentage()).hasToString("66.7");
        // limit 20.00: 1, 3, 5, 7 minor units are 0.05%, 0.15%, 0.25%, 0.35%: exact ties, HALF_EVEN rounds to even
        assertThat(of(2_000, 1).percentage()).hasToString("0.0");
        assertThat(of(2_000, 3).percentage()).hasToString("0.2");
        assertThat(of(2_000, 5).percentage()).hasToString("0.2");
        assertThat(of(2_000, 7).percentage()).hasToString("0.4");
    }
}
