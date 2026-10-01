package com.couplefinance.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link Money}, named after the BR-MON rules (business-rules.md §1). */
class MoneyTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final CurrencyCode JPY = new CurrencyCode("JPY");
    private static final CurrencyCode TND = new CurrencyCode("TND");
    private static final CurrencyCode USD = new CurrencyCode("USD");

    // --- BR-MON-01 ---------------------------------------------------------------------------------------------

    @Test
    void BR_MON_01_money_cannot_be_constructed_without_a_currency() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("12.50"), null, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void BR_MON_01_money_cannot_be_constructed_without_an_amount() {
        assertThatThrownBy(() -> Money.of(null, EUR, 2)).isInstanceOf(NullPointerException.class);
    }

    // --- BR-MON-02: exact decimals, round trips through minor units for 0/2/3-decimal currencies ---------------

    @Test
    void BR_MON_02_minor_unit_round_trip_for_a_zero_decimal_currency() {
        Money money = Money.parse("1500", JPY, 0);

        assertThat(money.minorUnits()).isEqualTo(1500L);
        assertThat(Money.ofMinor(1500L, JPY, 0)).isEqualTo(money);
    }

    @Test
    void BR_MON_02_minor_unit_round_trip_for_a_two_decimal_currency() {
        Money money = Money.parse("12.50", EUR, 2);

        assertThat(money.minorUnits()).isEqualTo(1250L);
        assertThat(Money.ofMinor(1250L, EUR, 2)).isEqualTo(money);
    }

    @Test
    void BR_MON_02_minor_unit_round_trip_for_a_three_decimal_currency() {
        Money money = Money.parse("12.505", TND, 3);

        assertThat(money.minorUnits()).isEqualTo(12505L);
        assertThat(Money.ofMinor(12505L, TND, 3)).isEqualTo(money);
    }

    @Test
    void BR_MON_02_of_rejects_an_amount_that_cannot_be_represented_exactly() {
        // 12.505 EUR cannot be represented with 2 decimals.
        assertThatThrownBy(() -> Money.of(new BigDecimal("12.505"), EUR, 2))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.TOO_MANY_DECIMALS));
    }

    @Test
    void BR_MON_02_of_accepts_an_amount_with_exact_trailing_zeros() {
        Money money = Money.of(new BigDecimal("12.5000"), EUR, 2);

        assertThat(money.toDecimalString()).isEqualTo("12.50");
    }

    // --- BR-MON-03: strict parsing ------------------------------------------------------------------------------

    @Test
    void BR_MON_03_parse_rejects_more_decimals_than_the_currency_allows_rather_than_rounding() {
        assertThatThrownBy(() -> Money.parse("12.505", EUR, 2))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.TOO_MANY_DECIMALS));
    }

    @Test
    void BR_MON_03_parse_rejects_any_decimals_for_a_zero_decimal_currency() {
        assertThatThrownBy(() -> Money.parse("1500.00", JPY, 0))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.TOO_MANY_DECIMALS));
    }

    @Test
    void BR_MON_03_parse_pads_fewer_decimals_than_the_currency_allows() {
        Money money = Money.parse("12.5", EUR, 2);

        assertThat(money.toDecimalString()).isEqualTo("12.50");
        assertThat(money.minorUnits()).isEqualTo(1250L);
    }

    @Test
    void BR_MON_03_parse_accepts_an_integral_amount() {
        Money money = Money.parse("12", EUR, 2);

        assertThat(money.toDecimalString()).isEqualTo("12.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1,234.50",   // thousands separator
            "12,50",      // locale-formatted (comma decimal separator)
            "1.25e1",     // exponent
            "+12.50",     // explicit plus sign
            "12.50 ",     // trailing whitespace
            " 12.50",     // leading whitespace
            "12..50",     // malformed
            "abc",        // not a number
            "",           // empty
            "012.50",     // leading zero padding
            "12.",        // trailing dot, no digits
            ".50"         // missing integer part
    })
    void BR_MON_03_parse_rejects_malformed_exponent_or_locale_formatted_input(String invalid) {
        assertThatThrownBy(() -> Money.parse(invalid, EUR, 2))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.INVALID_AMOUNT_FORMAT));
    }

    @Test
    void BR_MON_03_parse_rejects_a_null_amount() {
        assertThatThrownBy(() -> Money.parse(null, EUR, 2))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.INVALID_AMOUNT_FORMAT));
    }

    // --- BR-MON-04: no cross-currency operations ------------------------------------------------------------

    @Test
    void BR_MON_04_adding_different_currencies_throws() {
        Money eur = Money.parse("10.00", EUR, 2);
        Money usd = Money.parse("10.00", USD, 2);

        assertThatThrownBy(() -> eur.add(usd))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.CURRENCY_MISMATCH));
    }

    @Test
    void BR_MON_04_subtracting_different_currencies_throws() {
        Money eur = Money.parse("10.00", EUR, 2);
        Money usd = Money.parse("10.00", USD, 2);

        assertThatThrownBy(() -> eur.subtract(usd))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.CURRENCY_MISMATCH));
    }

    @Test
    void BR_MON_04_comparing_different_currencies_throws() {
        Money eur = Money.parse("10.00", EUR, 2);
        Money usd = Money.parse("10.00", USD, 2);

        assertThatThrownBy(() -> eur.compareTo(usd))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.CURRENCY_MISMATCH));
    }

    @Test
    void BR_MON_04_comparison_uses_compareTo_not_equals_on_differing_representations() {
        Money fromParse = Money.parse("12.5", EUR, 2);
        Money fromMinor = Money.ofMinor(1250L, EUR, 2);

        assertThat(fromParse.compareTo(fromMinor)).isZero();
        assertThat(fromParse).isEqualTo(fromMinor);
    }

    @Test
    void BR_MON_04_same_currency_amounts_combine_and_compare() {
        Money ten = Money.parse("10.00", EUR, 2);
        Money five = Money.parse("5.00", EUR, 2);

        assertThat(ten.add(five)).isEqualTo(Money.parse("15.00", EUR, 2));
        assertThat(ten.subtract(five)).isEqualTo(Money.parse("5.00", EUR, 2));
        assertThat(ten.compareTo(five)).isPositive();
    }

    // --- BR-MON-05: rounding applied once, HALF_EVEN ----------------------------------------------------------

    @Test
    void BR_MON_05_rounded_applies_half_even_to_the_minor_unit() {
        // 12.345 EUR -> HALF_EVEN to 2 decimals -> 12.34 (nearest even digit)
        assertThat(Money.rounded(new BigDecimal("12.345"), EUR, 2).toDecimalString()).isEqualTo("12.34");
        // 12.335 EUR -> HALF_EVEN to 2 decimals -> 12.34
        assertThat(Money.rounded(new BigDecimal("12.335"), EUR, 2).toDecimalString()).isEqualTo("12.34");
    }

    // --- BR-MON-06: deterministic split -------------------------------------------------------------------------

    @Test
    void BR_MON_06_split_distributes_remainder_first_part_first() {
        Money total = Money.parse("10.00", EUR, 2); // 1000 minor units

        List<Money> shares = total.split(3);

        assertThat(shares).extracting(Money::minorUnits).containsExactly(334L, 333L, 333L);
    }

    @Test
    void BR_MON_06_split_parts_always_sum_to_the_original_amount() {
        Money total = Money.parse("10.01", EUR, 2);

        List<Money> shares = total.split(4);

        Money sum = shares.stream().reduce(Money.zero(EUR, 2), Money::add);
        assertThat(sum).isEqualTo(total);
    }

    @Test
    void BR_MON_06_split_into_one_part_returns_the_whole_amount() {
        Money total = Money.parse("42.00", EUR, 2);

        assertThat(total.split(1)).containsExactly(total);
    }

    @Test
    void BR_MON_06_split_rejects_fewer_than_one_part() {
        Money total = Money.parse("10.00", EUR, 2);

        assertThatThrownBy(() -> total.split(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- BR-MON-07: positive, bounded by a caller-supplied maximum --------------------------------------------

    @Test
    void BR_MON_07_requires_a_strictly_positive_amount() {
        Money maximum = Money.parse("1000000.00", EUR, 2);

        assertThatThrownBy(() -> Money.zero(EUR, 2).requirePositiveAtMost(maximum))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.AMOUNT_NOT_POSITIVE));
    }

    @Test
    void BR_MON_07_rejects_a_negative_amount() {
        Money negative = Money.of(new BigDecimal("-5.00"), EUR, 2);
        Money maximum = Money.parse("1000000.00", EUR, 2);

        assertThatThrownBy(() -> negative.requirePositiveAtMost(maximum))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.AMOUNT_NOT_POSITIVE));
    }

    @Test
    void BR_MON_07_rejects_an_amount_above_the_caller_supplied_maximum() {
        Money amount = Money.parse("1000000.01", EUR, 2);
        Money maximum = Money.parse("1000000.00", EUR, 2);

        assertThatThrownBy(() -> amount.requirePositiveAtMost(maximum))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.AMOUNT_EXCEEDS_MAXIMUM));
    }

    @Test
    void BR_MON_07_accepts_an_amount_at_exactly_the_maximum() {
        Money amount = Money.parse("1000000.00", EUR, 2);
        Money maximum = Money.parse("1000000.00", EUR, 2);

        amount.requirePositiveAtMost(maximum);
    }

    @Test
    void BR_MON_07_maximum_check_does_not_hard_code_a_table_different_currencies_use_different_limits() {
        Money jpyAmount = Money.parse("150000000", JPY, 0);
        Money jpyMaximum = Money.parse("150000000", JPY, 0);

        jpyAmount.requirePositiveAtMost(jpyMaximum);
    }

    // --- BR-MON-09: percentage from minor units, HALF_EVEN to one decimal -------------------------------------

    @Test
    void BR_MON_09_percentage_is_computed_from_minor_units_and_rounded_half_even_to_one_decimal() {
        Money part = Money.parse("33.33", EUR, 2);
        Money whole = Money.parse("100.00", EUR, 2);

        assertThat(part.percentageOf(whole)).isEqualByComparingTo("33.3");
    }

    @Test
    void BR_MON_09_percentage_requires_the_same_currency() {
        Money part = Money.parse("10.00", EUR, 2);
        Money whole = Money.parse("100.00", USD, 2);

        assertThatThrownBy(() -> part.percentageOf(whole))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.CURRENCY_MISMATCH));
    }

    @Test
    void BR_MON_09_percentage_of_a_zero_whole_is_rejected() {
        Money part = Money.parse("10.00", EUR, 2);

        assertThatThrownBy(() -> part.percentageOf(Money.zero(EUR, 2)))
                .isInstanceOf(ArithmeticException.class);
    }

    // --- general invariants --------------------------------------------------------------------------------------

    @Test
    void decimals_outside_zero_to_four_are_rejected() {
        assertThatThrownBy(() -> Money.zero(EUR, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.zero(EUR, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_never_mixes_currencies_and_is_a_plain_amount_followed_by_the_code() {
        assertThat(Money.parse("12.50", EUR, 2)).hasToString("12.50 EUR");
    }
}
