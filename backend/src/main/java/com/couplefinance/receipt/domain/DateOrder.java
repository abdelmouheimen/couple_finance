package com.couplefinance.receipt.domain;

import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Optional;

/** Order of the day, month and year parts in a numeric date. */
public enum DateOrder {
    DMY,
    MDY,
    YMD;

    /** Short-date order of a locale (language required; a bare root locale carries no information). */
    public static Optional<DateOrder> forLocale(Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return Optional.empty();
        }
        String pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null,
                IsoChronology.INSTANCE, locale);
        int day = pattern.indexOf('d');
        int month = pattern.indexOf('M');
        int year = Math.max(pattern.indexOf('y'), pattern.indexOf('u'));
        if (day < 0 || month < 0 || year < 0) {
            return Optional.empty();
        }
        if (year < day && year < month) {
            return Optional.of(YMD);
        }
        return day < month ? Optional.of(DMY) : Optional.of(MDY);
    }
}
