package com.couplefinance.receipt.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Hints used to disambiguate a date string (BR-RCP-05): the {@code dateOrder} reported by the model in
 * {@code observedFormats} and the receipt/household locales.
 */
public record DateHints(Optional<DateOrder> observedOrder, List<Locale> locales) {

    public DateHints {
        observedOrder = observedOrder == null ? Optional.empty() : observedOrder;
        locales = locales == null ? List.of() : locales.stream().filter(Objects::nonNull).toList();
    }

    public static DateHints none() {
        return new DateHints(Optional.empty(), List.of());
    }
}
