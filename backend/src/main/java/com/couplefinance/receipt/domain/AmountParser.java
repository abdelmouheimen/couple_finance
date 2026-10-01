package com.couplefinance.receipt.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.couplefinance.shared.money.Money;

/**
 * Deterministic parsing of an amount transcribed as printed (BR-RCP-04/05, BR-MON-03/07). Total: never throws on
 * arbitrary input, bounded by {@link #MAX_LENGTH}, no I/O, no logging. Enumerates every valid reading of the string
 * (e.g. {@code 1.234} → 1234 or 1.234) and resolves to one only when all hints agree; otherwise the result is
 * {@link ParseResult.Ambiguous}. Nothing is rounded, clamped or corrected.
 */
public final class AmountParser {

    static final int MAX_LENGTH = 40;
    private static final Pattern BODY = Pattern.compile("[0-9]([0-9.,' ]*[0-9])?");
    private static final Pattern FIRST_GROUP = Pattern.compile("[1-9][0-9]{0,2}");
    private static final Pattern PLAIN_INTEGER = Pattern.compile("0|[1-9][0-9]*");

    private AmountParser() {
    }

    /**
     * @param raw     the amount as printed (sign allowed, no currency symbol)
     * @param maximum BR-MON-07 per-currency maximum; its currency and scale (decimals) define the target currency
     * @param hints   disambiguation hints
     */
    public static ParseResult<Money> parse(String raw, Money maximum, AmountHints hints) {
        Objects.requireNonNull(maximum, "maximum");
        Objects.requireNonNull(hints, "hints");
        Outcome outcome = candidates(raw, maximum);
        if (outcome.failure != null) {
            return new ParseResult.Invalid<>(outcome.failure);
        }
        List<Candidate> candidates = outcome.candidates;
        if (distinctValues(candidates).size() == 1) {
            return resolved(candidates.get(0).money);
        }
        Set<DecimalMark> votes = votes(hints, maximum);
        if (votes.size() == 1) {
            DecimalMark mark = votes.iterator().next();
            List<Candidate> consistent = candidates.stream().filter(c -> c.reading.consistentWith(mark)).toList();
            if (distinctValues(consistent).size() == 1) {
                return resolved(consistent.get(0).money);
            }
            if (!consistent.isEmpty()) {
                candidates = consistent;
            }
        }
        return new ParseResult.Ambiguous<>(distinctValues(candidates));
    }

    private static ParseResult<Money> resolved(Money money) {
        return new ParseResult.Resolved<>(money, Set.of());
    }

    private static List<Money> distinctValues(List<Candidate> candidates) {
        return new ArrayList<>(new LinkedHashSet<>(candidates.stream().map(c -> c.money).toList()));
    }

    private static Set<DecimalMark> votes(AmountHints hints, Money maximum) {
        Set<DecimalMark> votes = EnumSet.noneOf(DecimalMark.class);
        hints.observedDecimalMark().ifPresent(votes::add);
        hints.locales().forEach(locale -> DecimalMark.forLocale(locale).ifPresent(votes::add));
        for (String other : hints.otherAmounts()) {
            Outcome outcome = candidates(other, maximum);
            if (outcome.failure != null) {
                continue;
            }
            Set<Optional<DecimalMark>> implied = new LinkedHashSet<>();
            outcome.candidates.forEach(c -> implied.add(c.reading.impliedMark()));
            if (implied.size() == 1 && implied.iterator().next().isPresent()) {
                votes.add(implied.iterator().next().get());
            }
        }
        return votes;
    }

    // --- reading enumeration --------------------------------------------------------------------------------

    private record Reading(BigDecimal magnitude, int fractionDigits, Optional<Character> decimal,
                           Optional<Character> grouping, int groupingCount) {

        boolean consistentWith(DecimalMark mark) {
            if (decimal.isPresent()) {
                return decimal.get() == mark.symbol();
            }
            return grouping.map(g -> g != mark.symbol()).orElse(true);
        }

        /** Decimal mark this reading proves, if any (a repeated {@code .}/{@code ,} grouping proves the other). */
        Optional<DecimalMark> impliedMark() {
            if (decimal.isPresent()) {
                return DecimalMark.of(decimal.get());
            }
            if (groupingCount >= 2 && grouping.isPresent()) {
                return DecimalMark.of(grouping.get()).map(DecimalMark::opposite);
            }
            return Optional.empty();
        }
    }

    private record Candidate(Reading reading, Money money) {
    }

    private record Outcome(List<Candidate> candidates, InvalidReason failure) {
        static Outcome failed(InvalidReason reason) {
            return new Outcome(List.of(), reason);
        }
    }

    private static Outcome candidates(String raw, Money maximum) {
        if (raw == null) {
            return Outcome.failed(InvalidReason.UNPARSABLE);
        }
        if (raw.length() > MAX_LENGTH) {
            return Outcome.failed(InvalidReason.TOO_LONG);
        }
        String text = raw.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace('’', '\'').replace('−', '-').strip();
        boolean negative = false;
        if (text.startsWith("-")) {
            negative = true;
            text = text.substring(1).strip();
        } else if (text.endsWith("-")) {
            negative = true;
            text = text.substring(0, text.length() - 1).strip();
        }
        if (!BODY.matcher(text).matches()) {
            return Outcome.failed(InvalidReason.UNPARSABLE);
        }
        List<Reading> readings = readings(text);
        if (readings.isEmpty()) {
            return Outcome.failed(InvalidReason.UNPARSABLE);
        }
        int decimals = maximum.amount().scale();
        boolean outOfRange = false;
        List<Candidate> candidates = new ArrayList<>();
        for (Reading reading : readings) {
            if (reading.fractionDigits > decimals) {
                continue;
            }
            if (reading.magnitude.compareTo(maximum.amount()) > 0) {
                outOfRange = true;
                continue;
            }
            BigDecimal signed = negative ? reading.magnitude.negate() : reading.magnitude;
            candidates.add(new Candidate(reading, Money.of(signed, maximum.currency(), decimals)));
        }
        if (candidates.isEmpty()) {
            return Outcome.failed(outOfRange ? InvalidReason.OUT_OF_RANGE : InvalidReason.TOO_MANY_DECIMALS);
        }
        return new Outcome(candidates, null);
    }

    private static List<Reading> readings(String body) {
        List<String> groups = new ArrayList<>();
        List<Character> separators = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c >= '0' && c <= '9') {
                current.append(c);
            } else {
                if (current.isEmpty()) {
                    return List.of(); // two consecutive separators
                }
                groups.add(current.toString());
                separators.add(c);
                current.setLength(0);
            }
        }
        groups.add(current.toString());

        List<Reading> readings = new ArrayList<>(2);
        if (groups.size() == 1) {
            if (PLAIN_INTEGER.matcher(groups.get(0)).matches()) {
                readings.add(new Reading(new BigDecimal(groups.get(0)), 0, Optional.empty(), Optional.empty(), 0));
            }
            return readings;
        }
        int last = groups.size() - 1;
        // Reading A: the last separator is the (single) decimal separator.
        char decimalChar = separators.get(last - 1);
        if (decimalChar == '.' || decimalChar == ',') {
            List<Character> integerSeparators = separators.subList(0, last - 1);
            Optional<Character> grouping = sameSeparator(integerSeparators);
            boolean groupingOk = integerSeparators.isEmpty()
                    ? PLAIN_INTEGER.matcher(groups.get(0)).matches()
                    : grouping.isPresent() && grouping.get() != decimalChar
                            && validGrouping(groups.subList(0, last));
            if (groupingOk) {
                String integer = String.join("", groups.subList(0, last));
                String fraction = groups.get(last);
                readings.add(new Reading(new BigDecimal(integer + "." + fraction), fraction.length(),
                        Optional.of(decimalChar), grouping, integerSeparators.size()));
            }
        }
        // Reading B: every separator is a thousands separator.
        Optional<Character> grouping = sameSeparator(separators);
        if (grouping.isPresent() && validGrouping(groups)) {
            readings.add(new Reading(new BigDecimal(String.join("", groups)), 0, Optional.empty(), grouping,
                    separators.size()));
        }
        return readings;
    }

    private static Optional<Character> sameSeparator(List<Character> separators) {
        if (separators.isEmpty() || separators.stream().anyMatch(c -> !c.equals(separators.get(0)))) {
            return Optional.empty();
        }
        return Optional.of(separators.get(0));
    }

    private static boolean validGrouping(List<String> groups) {
        if (!FIRST_GROUP.matcher(groups.get(0)).matches()) {
            return false;
        }
        return groups.subList(1, groups.size()).stream().allMatch(g -> g.length() == 3);
    }
}
