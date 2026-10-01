package com.couplefinance.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.couplefinance.shared.error.ApplicationException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the monetary invariants called out by CLAUDE.md §8 and architecture.md §8: split
 * totals, minor-unit round trips, parse/format round trips, and the currency-mixing guard.
 */
class MoneyPropertyTest {

    private static final List<Integer> DECIMALS = List.of(0, 1, 2, 3, 4);
    private static final CurrencyCode CURRENCY = new CurrencyCode("EUR");

    @Property
    void BR_MON_06_split_parts_always_sum_to_the_original_amount(
            @ForAll("minorUnits") long minorUnits, @ForAll("decimals") int decimals, @ForAll("parts") int parts) {
        Money total = Money.ofMinor(minorUnits, CURRENCY, decimals);

        List<Money> shares = total.split(parts);

        assertThat(shares).hasSize(parts);
        Money sum = shares.stream().reduce(Money.zero(CURRENCY, decimals), Money::add);
        assertThat(sum).isEqualTo(total);
    }

    @Property
    void BR_MON_06_split_shares_differ_by_at_most_one_minor_unit(
            @ForAll("minorUnits") long minorUnits, @ForAll("decimals") int decimals, @ForAll("parts") int parts) {
        List<Money> shares = Money.ofMinor(minorUnits, CURRENCY, decimals).split(parts);

        long max = shares.stream().mapToLong(Money::minorUnits).max().orElseThrow();
        long min = shares.stream().mapToLong(Money::minorUnits).min().orElseThrow();
        assertThat(max - min).isLessThanOrEqualTo(1);
    }

    @Property
    void BR_MON_02_minor_unit_round_trip(@ForAll("minorUnits") long minorUnits, @ForAll("decimals") int decimals) {
        Money money = Money.ofMinor(minorUnits, CURRENCY, decimals);

        assertThat(money.minorUnits()).isEqualTo(minorUnits);
    }

    @Property
    void BR_MON_03_parse_format_round_trip(@ForAll("minorUnits") long minorUnits, @ForAll("decimals") int decimals) {
        Money original = Money.ofMinor(minorUnits, CURRENCY, decimals);

        Money reparsed = Money.parse(original.toDecimalString(), CURRENCY, decimals);

        assertThat(reparsed).isEqualTo(original);
        assertThat(reparsed.minorUnits()).isEqualTo(minorUnits);
    }

    @Property
    void BR_MON_04_operations_never_mix_two_different_currencies(
            @ForAll("minorUnits") long aMinor, @ForAll("minorUnits") long bMinor,
            @ForAll("distinctCurrencyPair") CurrencyPair currencies) {
        Money a = Money.ofMinor(aMinor, currencies.first(), 2);
        Money b = Money.ofMinor(bMinor, currencies.second(), 2);

        assertThatThrownBy(() -> a.add(b))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.CURRENCY_MISMATCH));
    }

    @Provide
    Arbitrary<Long> minorUnits() {
        return Arbitraries.longs().between(0, 1_000_000_000_000L);
    }

    @Provide
    Arbitrary<Integer> decimals() {
        return Arbitraries.of(DECIMALS);
    }

    @Provide
    Arbitrary<Integer> parts() {
        return Arbitraries.integers().between(1, 37);
    }

    @Provide
    Arbitrary<CurrencyPair> distinctCurrencyPair() {
        Arbitrary<String> code = Arbitraries.of("EUR", "USD", "GBP", "JPY", "TND");
        return Combinators.combine(code, code)
                .as(CurrencyPair::new)
                .filter(pair -> !pair.firstCode().equals(pair.secondCode()));
    }

    record CurrencyPair(String firstCode, String secondCode) {
        CurrencyCode first() {
            return new CurrencyCode(firstCode);
        }

        CurrencyCode second() {
            return new CurrencyCode(secondCode);
        }
    }
}
