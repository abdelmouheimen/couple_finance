package com.couplefinance.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;

/** Property-based tests: totality, round-trip and candidate validity (BR-RCP-04/05, BR-MON-07). */
class ReceiptParsingPropertyTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final Money MAX = Money.ofMinor(100_000_000L, EUR, 2);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Property(tries = 3000)
    void BR_RCP_04_amount_parser_never_throws(@ForAll @StringLength(max = 60) String raw) {
        assertThat(AmountParser.parse(raw, MAX, AmountHints.none())).isNotNull();
        assertThat(AmountParser.parse(raw, MAX, new AmountHints(Optional.of(DecimalMark.COMMA), List.of(),
                List.of(raw)))).isNotNull();
    }

    @Property(tries = 3000)
    void BR_RCP_04_date_parser_never_throws(@ForAll @StringLength(max = 30) String raw) {
        assertThat(ReceiptDateParser.parse(raw, DateHints.none(), TODAY)).isNotNull();
    }

    @Property(tries = 3000)
    void BR_RCP_04_currency_parser_never_throws(@ForAll @StringLength(max = 30) String raw) {
        assertThat(CurrencyParser.parse(raw, List.of(), EUR)).isNotNull();
    }

    @Property(tries = 3000)
    void BR_RCP_04_digit_like_strings_never_throw(
            @ForAll @net.jqwik.api.constraints.CharRange(from = '0', to = '9') @net.jqwik.api.constraints.Chars({'.', ',', ' ', '-', '/'})
            @StringLength(max = 25) String raw) {
        assertThat(AmountParser.parse(raw, MAX, AmountHints.none())).isNotNull();
        assertThat(ReceiptDateParser.parse(raw, DateHints.none(), TODAY)).isNotNull();
    }

    @Property(tries = 2000)
    void BR_RCP_04_resolved_amounts_round_trip(@ForAll @LongRange(min = -100_000_000L, max = 100_000_000L) long minor) {
        Money money = Money.ofMinor(minor, EUR, 2);
        String printed = money.toDecimalString().replace('.', ',');
        ParseResult<Money> result = AmountParser.parse(printed,  MAX, new AmountHints(
                Optional.of(DecimalMark.COMMA), List.of(), List.of()));
        assertThat(result).isEqualTo(new ParseResult.Resolved<>(money, java.util.Set.of()));
    }

    @Property(tries = 2000)
    void BR_RCP_05_ambiguity_candidates_are_distinct_valid_in_range_readings(
            @ForAll @LongRange(min = 0, max = 999_999) long units) {
        Money tnd = Money.ofMinor(1_000_000_000L, new CurrencyCode("TND"), 3);
        String printed = (units / 1000) + "." + String.format("%03d", units % 1000);
        if (units / 1000 == 0) {
            return; // "0.xxx" has a single reading
        }
        ParseResult<Money> result = AmountParser.parse(printed, tnd, AmountHints.none());
        assertThat(result).isInstanceOf(ParseResult.Ambiguous.class);
        List<Money> candidates = ((ParseResult.Ambiguous<Money>) result).candidates();
        assertThat(candidates).doesNotHaveDuplicates().allSatisfy(m -> {
            assertThat(m.amount().abs()).isLessThanOrEqualTo(tnd.amount());
            assertThat(m.currency()).isEqualTo(tnd.currency());
        });
    }

    @Property(tries = 2000)
    void BR_RCP_08_resolved_dates_are_within_bounds(@ForAll @StringLength(max = 12) String raw) {
        if (ReceiptDateParser.parse(raw, DateHints.none(), TODAY) instanceof ParseResult.Resolved<LocalDate> r) {
            assertThat(r.value()).isBetween(TODAY.minusYears(5), TODAY.plusDays(1));
        }
    }
}
