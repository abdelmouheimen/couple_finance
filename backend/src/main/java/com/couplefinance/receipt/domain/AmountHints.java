package com.couplefinance.receipt.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Hints used to disambiguate an amount string (BR-RCP-05, ai.md §4.3). A reading is chosen only if every
 * available hint agrees.
 *
 * @param observedDecimalMark decimal separator reported by the model in {@code observedFormats}
 * @param locales             receipt language/country and household locale
 * @param otherAmounts        the other raw amount strings of the same receipt (e.g. {@code 12,50} proves that
 *                            {@code ,} is the decimal separator)
 */
public record AmountHints(Optional<DecimalMark> observedDecimalMark, List<Locale> locales,
                          List<String> otherAmounts) {

    /** Bound on the number of other amounts considered (a receipt has at most 300 line items). */
    public static final int MAX_OTHER_AMOUNTS = 400;

    public AmountHints {
        observedDecimalMark = observedDecimalMark == null ? Optional.empty() : observedDecimalMark;
        locales = locales == null ? List.of() : locales.stream().filter(Objects::nonNull).toList();
        otherAmounts = otherAmounts == null ? List.of()
                : otherAmounts.stream().filter(Objects::nonNull).limit(MAX_OTHER_AMOUNTS).toList();
    }

    public static AmountHints none() {
        return new AmountHints(Optional.empty(), List.of(), List.of());
    }
}
