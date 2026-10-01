package com.couplefinance.receipt.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic parsing of a purchase date transcribed as printed (BR-RCP-04/05/08). Total, bounded, no I/O.
 * "Today" is a parameter (the caller computes it from an injected {@code Clock} in the household time zone,
 * BR-HH-05). Readings outside BR-RCP-08 bounds (more than 1 day after today, more than 5 years old) are dropped;
 * more than one remaining reading with no agreeing hint is {@link ParseResult.Ambiguous}.
 */
public final class ReceiptDateParser {

    static final int MAX_LENGTH = 20;
    private static final Pattern NUMERIC = Pattern.compile("([0-9]{1,4})([./ -])([0-9]{1,2})\\2([0-9]{1,4})");

    private ReceiptDateParser() {
    }

    public static ParseResult<LocalDate> parse(String raw, DateHints hints, LocalDate today) {
        Objects.requireNonNull(hints, "hints");
        Objects.requireNonNull(today, "today");
        if (raw == null) {
            return new ParseResult.Invalid<>(InvalidReason.UNPARSABLE);
        }
        if (raw.length() > MAX_LENGTH) {
            return new ParseResult.Invalid<>(InvalidReason.TOO_LONG);
        }
        Matcher matcher = NUMERIC.matcher(raw.replace(' ', ' ').strip());
        if (!matcher.matches()) {
            return new ParseResult.Invalid<>(InvalidReason.UNPARSABLE);
        }
        String a = matcher.group(1);
        String b = matcher.group(3);
        String c = matcher.group(4);

        List<Reading> readings = new ArrayList<>(3);
        if (a.length() == 4 && c.length() <= 2) {
            add(readings, DateOrder.YMD, a, b, c);
        } else if (a.length() <= 2 && c.length() == 4) {
            add(readings, DateOrder.DMY, c, b, a);
            add(readings, DateOrder.MDY, c, a, b);
        } else if (a.length() <= 2 && c.length() == 2) {
            add(readings, DateOrder.DMY, c, b, a);
            add(readings, DateOrder.MDY, c, a, b);
            add(readings, DateOrder.YMD, a, b, c);
        } else {
            return new ParseResult.Invalid<>(InvalidReason.UNPARSABLE);
        }
        if (readings.isEmpty()) {
            return new ParseResult.Invalid<>(InvalidReason.INVALID_DATE);
        }
        LocalDate earliest = today.minusYears(5);
        LocalDate latest = today.plusDays(1);
        List<Reading> inBounds = readings.stream()
                .filter(r -> !r.date.isBefore(earliest) && !r.date.isAfter(latest)).toList();
        if (inBounds.isEmpty()) {
            return new ParseResult.Invalid<>(InvalidReason.DATE_OUT_OF_BOUNDS);
        }
        if (distinct(inBounds).size() == 1) {
            return resolved(inBounds.get(0).date, today);
        }
        Set<DateOrder> votes = EnumSet.noneOf(DateOrder.class);
        hints.observedOrder().ifPresent(votes::add);
        hints.locales().forEach(locale -> DateOrder.forLocale(locale).ifPresent(votes::add));
        List<Reading> candidates = inBounds;
        if (votes.size() == 1) {
            DateOrder order = votes.iterator().next();
            List<Reading> matching = inBounds.stream().filter(r -> r.order == order).toList();
            if (distinct(matching).size() == 1) {
                return resolved(matching.get(0).date, today);
            }
            if (!matching.isEmpty()) {
                candidates = matching;
            }
        }
        return new ParseResult.Ambiguous<>(distinct(candidates));
    }

    /** BR-RCP-08: warnings for a chosen date (older than one year → {@link ParseWarning#STALE}). */
    public static Set<ParseWarning> warningsFor(LocalDate date, LocalDate today) {
        return date.isBefore(today.minusYears(1)) ? Set.of(ParseWarning.STALE) : Set.of();
    }

    private static ParseResult<LocalDate> resolved(LocalDate date, LocalDate today) {
        return new ParseResult.Resolved<>(date, warningsFor(date, today));
    }

    private record Reading(DateOrder order, LocalDate date) {
    }

    private static List<LocalDate> distinct(List<Reading> readings) {
        return new ArrayList<>(new LinkedHashSet<>(readings.stream().map(r -> r.date).toList()));
    }

    private static void add(List<Reading> readings, DateOrder order, String year, String month, String day) {
        try {
            int y = year.length() == 2 ? 2000 + Integer.parseInt(year) : Integer.parseInt(year);
            readings.add(new Reading(order, LocalDate.of(y, Integer.parseInt(month), Integer.parseInt(day))));
        } catch (DateTimeException e) {
            // not a calendar date: this reading is dropped
        }
    }
}
