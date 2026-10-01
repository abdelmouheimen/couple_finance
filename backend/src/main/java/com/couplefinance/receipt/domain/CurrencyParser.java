package com.couplefinance.receipt.domain;

import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.couplefinance.shared.money.CurrencyCode;

/**
 * Deterministic interpretation of the currency symbol or code transcribed from a receipt (BR-RCP-04/05/09).
 * ISO codes, the euro and the pound sign are unambiguous; {@code $}, {@code kr} and the yen/yuan sign resolve only
 * when every country hint points to the same candidate currency, otherwise the result is
 * {@link ParseResult.Ambiguous}. Total, bounded, no I/O.
 */
public final class CurrencyParser {

    static final int MAX_LENGTH = 16;

    private static final Map<String, CurrencyCode> UNAMBIGUOUS = Map.of(
            "€", new CurrencyCode("EUR"),
            "£", new CurrencyCode("GBP"));

    private static final List<CurrencyCode> DOLLAR = codes("USD", "CAD", "AUD", "NZD", "MXN", "SGD", "HKD");
    private static final List<CurrencyCode> KRONA = codes("SEK", "NOK", "DKK", "ISK");
    private static final List<CurrencyCode> YEN = codes("JPY", "CNY");

    private static final Map<String, List<CurrencyCode>> AMBIGUOUS_SYMBOLS = Map.of(
            "$", DOLLAR,
            "kr", KRONA,
            "kr.", KRONA,
            "¥", YEN,
            "￥", YEN,
            "yen", YEN);

    private CurrencyParser() {
    }

    /**
     * @param raw       symbol or ISO code as printed
     * @param hints     receipt country/language and household locale; only locales with a country carry a currency
     * @param household BR-RCP-09: a resolved currency different from this one is flagged
     */
    public static ParseResult<CurrencyCode> parse(String raw, List<Locale> hints, CurrencyCode household) {
        Objects.requireNonNull(household, "household");
        List<Locale> locales = hints == null ? List.of() : hints;
        if (raw == null) {
            return new ParseResult.Invalid<>(InvalidReason.UNPARSABLE);
        }
        if (raw.length() > MAX_LENGTH) {
            return new ParseResult.Invalid<>(InvalidReason.TOO_LONG);
        }
        String text = raw.strip();
        CurrencyCode symbol = UNAMBIGUOUS.get(text);
        if (symbol != null) {
            return resolved(symbol, household);
        }
        List<CurrencyCode> candidates = AMBIGUOUS_SYMBOLS.get(text.toLowerCase(Locale.ROOT));
        if (candidates != null) {
            return fromHints(candidates, locales, household);
        }
        if (text.matches("[A-Za-z]{3}")) {
            String code = text.toUpperCase(Locale.ROOT);
            if (isIsoCurrency(code)) {
                return resolved(new CurrencyCode(code), household);
            }
            return new ParseResult.Invalid<>(InvalidReason.UNKNOWN_CURRENCY);
        }
        return new ParseResult.Invalid<>(InvalidReason.UNPARSABLE);
    }

    private static ParseResult<CurrencyCode> fromHints(List<CurrencyCode> candidates, List<Locale> locales,
                                                       CurrencyCode household) {
        Set<String> votes = new LinkedHashSet<>();
        for (Locale locale : locales) {
            if (locale != null && !locale.getCountry().isEmpty()) {
                try {
                    votes.add(Currency.getInstance(locale).getCurrencyCode());
                } catch (IllegalArgumentException | NullPointerException e) {
                    // no currency for this country: the hint carries no information
                }
            }
        }
        if (votes.size() == 1) {
            CurrencyCode voted = new CurrencyCode(votes.iterator().next());
            if (candidates.contains(voted)) {
                return resolved(voted, household);
            }
        }
        return new ParseResult.Ambiguous<>(candidates);
    }

    private static ParseResult<CurrencyCode> resolved(CurrencyCode currency, CurrencyCode household) {
        Set<ParseWarning> warnings = currency.equals(household)
                ? Set.of() : Set.of(ParseWarning.CURRENCY_DIFFERS_FROM_HOUSEHOLD);
        return new ParseResult.Resolved<>(currency, warnings);
    }

    private static boolean isIsoCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<CurrencyCode> codes(String... values) {
        return Arrays.stream(values).map(CurrencyCode::new).collect(Collectors.toUnmodifiableList());
    }
}
