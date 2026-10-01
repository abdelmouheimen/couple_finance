package com.couplefinance.receipt.domain;

import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;

/** The character a receipt uses as decimal separator. */
public enum DecimalMark {
    DOT('.'),
    COMMA(',');

    private final char symbol;

    DecimalMark(char symbol) {
        this.symbol = symbol;
    }

    public char symbol() {
        return symbol;
    }

    static Optional<DecimalMark> of(char c) {
        return c == '.' ? Optional.of(DOT) : c == ',' ? Optional.of(COMMA) : Optional.empty();
    }

    DecimalMark opposite() {
        return this == DOT ? COMMA : DOT;
    }

    /** Decimal mark of a locale (language required; a bare root locale carries no information). */
    public static Optional<DecimalMark> forLocale(Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return Optional.empty();
        }
        return of(DecimalFormatSymbols.getInstance(locale).getDecimalSeparator());
    }
}
