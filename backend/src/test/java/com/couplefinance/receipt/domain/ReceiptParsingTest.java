package com.couplefinance.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.couplefinance.receipt.domain.ParseResult.Ambiguous;
import com.couplefinance.receipt.domain.ParseResult.Invalid;
import com.couplefinance.receipt.domain.ParseResult.Resolved;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for BR-RCP-04/05/08/09 and BR-MON-07 deterministic receipt parsing. */
class ReceiptParsingTest {

    private static final CurrencyCode EUR = new CurrencyCode("EUR");
    private static final CurrencyCode TND = new CurrencyCode("TND");
    private static final CurrencyCode JPY = new CurrencyCode("JPY");
    private static final Money MAX_EUR = Money.ofMinor(100_000_000L, EUR, 2);
    private static final Money MAX_TND = Money.ofMinor(1_000_000_000L, TND, 3);
    private static final Money MAX_JPY = Money.ofMinor(150_000_000L, JPY, 0);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    private static final Locale FR = Locale.of("fr", "FR");
    private static final Locale DE = Locale.of("de", "DE");
    private static final Locale US = Locale.of("en", "US");
    private static final Locale GB = Locale.of("en", "GB");

    private static Money eur(String amount) {
        return Money.parse(amount, EUR, 2);
    }

    private static AmountHints locale(Locale locale) {
        return new AmountHints(Optional.empty(), List.of(locale), List.of());
    }

    private static ParseResult<Money> amount(String raw, AmountHints hints) {
        return AmountParser.parse(raw, MAX_EUR, hints);
    }

    // --- amounts: locale matrix (en / fr / de) -------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "'1,234.56', en, US, 1234.56",
            "'1.234,56', de, DE, 1234.56",
            "'1 234,56', fr, FR, 1234.56",
            "'1.234,56', fr, FR, 1234.56",
            "'12.50', en, US, 12.50",
            "'12,50', de, DE, 12.50",
            "'12,50', fr, FR, 12.50",
            "'1,234', en, US, 1234.00",
            "'1.234', de, DE, 1234.00",
            "'234.567,89', de, DE, 234567.89",
            "'0,50', fr, FR, 0.50",
            "'-12,50', fr, FR, -12.50",
            "'12,50-', fr, FR, -12.50",
            "'1 234,56', fr, FR, 1234.56",
            "'1’234.56', de, CH, 1234.56",
            "'7', en, US, 7.00"
    })
    void BR_RCP_04_amounts_in_en_fr_de_formats_are_parsed(String raw, String lang, String country, String expected) {
        ParseResult<Money> result = amount(raw, locale(Locale.of(lang, country)));
        assertThat(result).isEqualTo(new Resolved<>(eur(expected), Set.of()));
    }

    @Test
    void BR_RCP_05_ambiguous_amount_is_not_resolved_silently_for_a_three_decimal_currency() {
        ParseResult<Money> result = AmountParser.parse("1.234", MAX_TND, AmountHints.none());
        assertThat(result).isEqualTo(new Ambiguous<>(List.of(
                Money.parse("1.234", TND, 3), Money.parse("1234", TND, 3))));
        ParseResult<Money> comma = AmountParser.parse("1,234", MAX_TND, AmountHints.none());
        assertThat(comma).isInstanceOf(Ambiguous.class);
    }

    @Test
    void BR_RCP_05_currency_decimals_hint_removes_the_decimal_reading_for_eur() {
        assertThat(amount("1.234", AmountHints.none())).isEqualTo(new Resolved<>(eur("1234"), Set.of()));
        assertThat(amount("1,234", AmountHints.none())).isEqualTo(new Resolved<>(eur("1234"), Set.of()));
    }

    @Test
    void BR_RCP_05_resolved_only_when_all_hints_agree() {
        Money thousand = Money.parse("1234", TND, 3);
        Money decimal = Money.parse("1.234", TND, 3);
        AmountHints observedDot = new AmountHints(Optional.of(DecimalMark.DOT), List.of(), List.of());
        assertThat(AmountParser.parse("1.234", MAX_TND, observedDot)).isEqualTo(new Resolved<>(decimal, Set.of()));
        assertThat(AmountParser.parse("1.234", MAX_TND, locale(DE))).isEqualTo(new Resolved<>(thousand, Set.of()));

        AmountHints disagree = new AmountHints(Optional.of(DecimalMark.DOT), List.of(DE), List.of());
        assertThat(AmountParser.parse("1.234", MAX_TND, disagree)).isInstanceOf(Ambiguous.class);

        AmountHints agree = new AmountHints(Optional.of(DecimalMark.COMMA), List.of(FR, DE), List.of());
        assertThat(AmountParser.parse("1.234", MAX_TND, agree)).isEqualTo(new Resolved<>(thousand, Set.of()));
    }

    @Test
    void BR_RCP_05_other_amounts_on_the_receipt_are_a_hint() {
        AmountHints commaProven = new AmountHints(Optional.empty(), List.of(), List.of("12,50"));
        assertThat(AmountParser.parse("1.234", MAX_TND, commaProven))
                .isEqualTo(new Resolved<>(Money.parse("1234", TND, 3), Set.of()));
        AmountHints dotProven = new AmountHints(Optional.empty(), List.of(), List.of("12.500", "9.99"));
        assertThat(AmountParser.parse("1,234", MAX_EUR, dotProven)).isInstanceOf(Resolved.class);
        AmountHints inconclusive = new AmountHints(Optional.empty(), List.of(), List.of("12", "x"));
        assertThat(AmountParser.parse("1.234", MAX_TND, inconclusive)).isInstanceOf(Ambiguous.class);
    }

    @Test
    void BR_RCP_05_observed_mark_selects_the_only_consistent_reading() {
        // "1,234" under TND: decimal ',' or grouping ','; observed '.' makes only the grouping reading consistent.
        AmountHints dot = new AmountHints(Optional.of(DecimalMark.DOT), List.of(), List.of());
        assertThat(AmountParser.parse("1,234", MAX_TND, dot))
                .isEqualTo(new Resolved<>(Money.parse("1234", TND, 3), Set.of()));
    }

    @ParameterizedTest
    @CsvSource(value = {"''", "abc", "'1,,2'", "1.2.3", "01.50", ".50", "'12,50 EUR'", "1e3", "+5", "'1,2,3'", "-", "--5",
            "'1.234,567.8'", "12.5.00", "NaN", "٣٤"})
    void BR_RCP_04_unparsable_values_are_invalid(String raw) {
        assertThat(amount(raw, AmountHints.none())).isEqualTo(new Invalid<>(InvalidReason.UNPARSABLE));
    }

    @Test
    void BR_RCP_04_null_and_overlong_values_are_invalid() {
        assertThat(amount(null, AmountHints.none())).isEqualTo(new Invalid<>(InvalidReason.UNPARSABLE));
        assertThat(amount("1".repeat(41), AmountHints.none())).isEqualTo(new Invalid<>(InvalidReason.TOO_LONG));
    }

    @Test
    void BR_MON_07_out_of_range_amounts_are_invalid_and_never_clamped() {
        assertThat(amount("1 000 000,01", AmountHints.none())).isEqualTo(new Invalid<>(InvalidReason.OUT_OF_RANGE));
        assertThat(amount("-2000000", AmountHints.none())).isEqualTo(new Invalid<>(InvalidReason.OUT_OF_RANGE));
        assertThat(amount("1 000 000,00", AmountHints.none())).isInstanceOf(Resolved.class);
        assertThat(AmountParser.parse("150000001", MAX_JPY, AmountHints.none()))
                .isEqualTo(new Invalid<>(InvalidReason.OUT_OF_RANGE));
    }

    @Test
    void BR_RCP_04_excess_decimals_are_invalid_never_rounded() {
        assertThat(amount("12,5051", AmountHints.none())).isEqualTo(new Invalid<>(InvalidReason.TOO_MANY_DECIMALS));
        assertThat(AmountParser.parse("12.5", MAX_JPY, AmountHints.none()))
                .isEqualTo(new Invalid<>(InvalidReason.TOO_MANY_DECIMALS));
    }

    @Test
    void BR_MON_07_candidate_over_the_maximum_is_dropped_leaving_one_reading() {
        Money max = Money.parse("1000", TND, 3);
        assertThat(AmountParser.parse("1.234", max, AmountHints.none()))
                .isEqualTo(new Resolved<>(Money.parse("1.234", TND, 3), Set.of()));
        Money bigger = Money.parse("1500", TND, 3);
        assertThat(AmountParser.parse("1.234", bigger, AmountHints.none())).isInstanceOf(Ambiguous.class);
    }

    // --- line items ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "ITEM, 5.00, true", "ITEM, -5.00, false", "ITEM, 0.00, true",
            "DISCOUNT, -5.00, true", "DISCOUNT, 5.00, false", "DISCOUNT, 0.00, true",
            "DEPOSIT_RETURN, -0.25, true", "DEPOSIT_RETURN, 0.25, false",
            "DEPOSIT, 0.25, true", "DEPOSIT, -0.25, false",
            "TIP, 2.00, true", "TIP, -2.00, false",
            "FEE, 1.00, true", "FEE, -1.00, false",
            "OTHER, 1.00, true", "OTHER, -1.00, false"
    })
    void BR_RCP_04_line_item_sign_rules(LineItemType type, String amount, boolean valid) {
        ParseResult<Money> result = LineItemAmountParser.parse(type, amount, MAX_EUR, AmountHints.none());
        if (valid) {
            assertThat(result).isEqualTo(new Resolved<>(eur(amount), Set.of()));
        } else {
            assertThat(result).isEqualTo(new Invalid<>(InvalidReason.INVALID_SIGN));
        }
    }

    @Test
    void BR_RCP_04_negative_total_proposes_refund() {
        assertThat(ProposedKind.forTotal(eur("-12.50"))).isEqualTo(ProposedKind.REFUND);
        assertThat(ProposedKind.forTotal(eur("12.50"))).isEqualTo(ProposedKind.EXPENSE);
        assertThat(ProposedKind.forTotal(eur("0"))).isEqualTo(ProposedKind.EXPENSE);
    }

    // --- dates --------------------------------------------------------------------------------------------

    private static ParseResult<LocalDate> date(String raw, DateHints hints) {
        return ReceiptDateParser.parse(raw, hints, TODAY);
    }

    private static DateHints localeHint(Locale locale) {
        return new DateHints(Optional.empty(), List.of(locale));
    }

    @ParameterizedTest
    @CsvSource({
            "03/04/2026, fr, FR, 2026-04-03",
            "03/04/2026, en, US, 2026-03-04",
            "03/04/2026, en, GB, 2026-04-03",
            "03.04.2026, de, DE, 2026-04-03",
            "2026-04-03, en, US, 2026-04-03",
            "13/04/2026, en, US, 2026-04-13",
            "04/13/2026, fr, FR, 2026-04-13",
            "03/04/26, fr, FR, 2026-04-03"
    })
    void BR_RCP_05_dates_in_en_fr_de_formats(String raw, String lang, String country, String expected) {
        ParseResult<LocalDate> result = date(raw, localeHint(Locale.of(lang, country)));
        assertThat(result).isEqualTo(new Resolved<>(LocalDate.parse(expected), Set.of()));
    }

    @Test
    void BR_RCP_05_ambiguous_date_is_not_resolved_silently() {
        assertThat(date("03/04/2026", DateHints.none()))
                .isEqualTo(new Ambiguous<>(List.of(LocalDate.of(2026, 4, 3), LocalDate.of(2026, 3, 4))));
        DateHints disagree = new DateHints(Optional.of(DateOrder.DMY), List.of(US));
        assertThat(date("03/04/2026", disagree)).isInstanceOf(Ambiguous.class);
        DateHints agree = new DateHints(Optional.of(DateOrder.DMY), List.of(FR, GB));
        assertThat(date("03/04/2026", agree)).isEqualTo(new Resolved<>(LocalDate.of(2026, 4, 3), Set.of()));
    }

    @Test
    void BR_RCP_05_same_day_and_month_is_not_ambiguous() {
        assertThat(date("03/03/2026", DateHints.none()))
                .isEqualTo(new Resolved<>(LocalDate.of(2026, 3, 3), Set.of()));
    }

    @Test
    void BR_RCP_08_candidates_outside_bounds_are_dropped() {
        // 11/10/2026 -> 11 Oct (> today + 1 day, dropped) or 10 Nov (dropped as well); 10/11/2026 likewise.
        assertThat(date("11/10/2026", DateHints.none())).isEqualTo(new Invalid<>(InvalidReason.DATE_OUT_OF_BOUNDS));
        // 02/10/2026 -> 2 Oct (tomorrow, allowed) or 10 Feb (allowed): ambiguous; 05/10/2026 -> only 5 May.
        assertThat(date("05/10/2026", DateHints.none()))
                .isEqualTo(new Resolved<>(LocalDate.of(2026, 5, 10), Set.of()));
    }

    @Test
    void BR_RCP_08_one_day_after_today_is_allowed_two_is_not() {
        assertThat(date("2026-10-02", DateHints.none())).isInstanceOf(Resolved.class);
        assertThat(date("2026-10-03", DateHints.none())).isEqualTo(new Invalid<>(InvalidReason.DATE_OUT_OF_BOUNDS));
    }

    @Test
    void BR_RCP_08_five_year_bound_and_stale_warning() {
        assertThat(date("2021-10-01", DateHints.none())).isInstanceOf(Resolved.class);
        assertThat(date("2021-09-30", DateHints.none())).isEqualTo(new Invalid<>(InvalidReason.DATE_OUT_OF_BOUNDS));
        assertThat(date("2025-10-01", DateHints.none())).isEqualTo(new Resolved<>(LocalDate.of(2025, 10, 1), Set.of()));
        assertThat(date("2025-09-30", DateHints.none()))
                .isEqualTo(new Resolved<>(LocalDate.of(2025, 9, 30), Set.of(ParseWarning.STALE)));
    }

    @ParameterizedTest
    @CsvSource(value = {"''", "abc", "31/02/2026", "2026-13-01", "2026/00/10", "1/2/3", "123/4/2026", "3 April 2026",
            "03/04", "2026-04-03T10:00"})
    void BR_RCP_04_unparsable_or_impossible_dates_are_invalid(String raw) {
        assertThat(date(raw, DateHints.none())).isInstanceOf(Invalid.class);
    }

    @Test
    void BR_RCP_04_null_and_overlong_dates_are_invalid() {
        assertThat(date(null, DateHints.none())).isEqualTo(new Invalid<>(InvalidReason.UNPARSABLE));
        assertThat(date("1".repeat(21), DateHints.none())).isEqualTo(new Invalid<>(InvalidReason.TOO_LONG));
    }

    // --- currency -----------------------------------------------------------------------------------------

    private static ParseResult<CurrencyCode> currency(String raw, Locale... hints) {
        return CurrencyParser.parse(raw, List.of(hints), EUR);
    }

    @Test
    void BR_RCP_05_euro_pound_and_iso_codes_are_unambiguous() {
        assertThat(currency("€")).isEqualTo(new Resolved<>(EUR, Set.of()));
        assertThat(currency("£")).isEqualTo(
                new Resolved<>(new CurrencyCode("GBP"), Set.of(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD)));
        assertThat(currency("eur")).isEqualTo(new Resolved<>(EUR, Set.of()));
        assertThat(currency(" USD ")).isEqualTo(
                new Resolved<>(new CurrencyCode("USD"), Set.of(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD)));
    }

    @Test
    void BR_RCP_05_ambiguous_symbols_resolve_only_with_consistent_hints() {
        assertThat(currency("$")).isInstanceOf(Ambiguous.class);
        assertThat(currency("$", US)).isEqualTo(
                new Resolved<>(new CurrencyCode("USD"), Set.of(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD)));
        assertThat(currency("$", US, Locale.of("es", "US"))).isInstanceOf(Resolved.class);
        assertThat(currency("$", US, Locale.of("en", "CA"))).isInstanceOf(Ambiguous.class);
        assertThat(currency("$", FR)).isInstanceOf(Ambiguous.class);
        assertThat(currency("$", Locale.of("en"))).isInstanceOf(Ambiguous.class);
        assertThat(currency("kr")).isInstanceOf(Ambiguous.class);
        assertThat(currency("kr", Locale.of("sv", "SE"))).isEqualTo(
                new Resolved<>(new CurrencyCode("SEK"), Set.of(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD)));
        assertThat(currency("¥", Locale.JAPAN)).isEqualTo(
                new Resolved<>(JPY, Set.of(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD)));
        assertThat(currency("yen")).isInstanceOf(Ambiguous.class);
    }

    @Test
    void BR_RCP_09_currency_equal_to_household_has_no_flag() {
        assertThat(CurrencyParser.parse("$", List.of(US), new CurrencyCode("USD")))
                .isEqualTo(new Resolved<>(new CurrencyCode("USD"), Set.of()));
    }

    @Test
    void BR_RCP_04_unknown_or_unparsable_currencies_are_invalid() {
        assertThat(currency("ZZZ")).isEqualTo(new Invalid<>(InvalidReason.UNKNOWN_CURRENCY));
        assertThat(currency("??")).isEqualTo(new Invalid<>(InvalidReason.UNPARSABLE));
        assertThat(currency("x".repeat(17))).isEqualTo(new Invalid<>(InvalidReason.TOO_LONG));
        assertThat(CurrencyParser.parse(null, List.of(), EUR)).isEqualTo(new Invalid<>(InvalidReason.UNPARSABLE));
    }
}
