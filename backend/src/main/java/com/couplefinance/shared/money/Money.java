package com.couplefinance.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.couplefinance.shared.error.ApplicationException;

/**
 * An exact amount of a given currency (BR-MON-01/02): a {@link BigDecimal} amount, scaled to exactly the number
 * of minor-unit decimals of its {@link CurrencyCode} (EUR → 2, JPY → 0, TND → 3), paired with that currency.
 * Never {@code float}/{@code double} (CLAUDE.md §8).
 *
 * <p>The number of decimals a currency allows is reference data owned by the {@code household} module
 * ({@code household.currency.minor_units}), reached only through the {@link CurrencyDecimals} port — this value
 * object never performs I/O. Every public factory therefore takes {@code decimals} explicitly; once a
 * {@code Money} exists, its {@link BigDecimal#scale()} already equals its currency's decimals, so later
 * operations (arithmetic, {@link #split(int)}, {@link #minorUnits()}) never need to resolve decimals again.
 *
 * <p>Combining or comparing amounts of different currencies throws (BR-MON-04). Comparison and equality use
 * {@link BigDecimal#compareTo(BigDecimal)}, never {@link BigDecimal#equals(Object)}, since scale is not
 * significant to monetary equality.
 */
public final class Money implements Comparable<Money> {

    private static final Pattern STRICT_DECIMAL = Pattern.compile("^(-)?(0|[1-9]\\d*)(\\.(\\d+))?$");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final BigDecimal amount;
    private final CurrencyCode currency;

    private Money(BigDecimal amount, CurrencyCode currency) {
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    /**
     * Wraps an amount already computed in code. Rejects a value that cannot be represented exactly with
     * {@code decimals} digits (BR-MON-03) rather than rounding it.
     */
    public static Money of(BigDecimal amount, CurrencyCode currency, int decimals) {
        requireValidDecimals(decimals);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        try {
            return new Money(amount.setScale(decimals, RoundingMode.UNNECESSARY), currency);
        } catch (ArithmeticException e) {
            throw new ApplicationException(MoneyErrorCode.TOO_MANY_DECIMALS,
                    "Amount has more decimals than " + currency + " allows (" + decimals + ").");
        }
    }

    /** Builds a {@code Money} from an exact count of minor units (e.g. 1250 → 12.50 EUR). */
    public static Money ofMinor(long minorUnits, CurrencyCode currency, int decimals) {
        requireValidDecimals(decimals);
        Objects.requireNonNull(currency, "currency");
        return new Money(BigDecimal.valueOf(minorUnits, decimals), currency);
    }

    /** Zero amount of {@code currency}. */
    public static Money zero(CurrencyCode currency, int decimals) {
        return ofMinor(0L, currency, decimals);
    }

    /**
     * Strict parsing of a plain decimal string (BR-MON-02/03): no leading {@code +}, no thousands separators, no
     * exponent, no leading zero padding, and never more fractional digits than {@code decimals} allows — such
     * input is rejected, never rounded.
     */
    public static Money parse(String text, CurrencyCode currency, int decimals) {
        requireValidDecimals(decimals);
        Objects.requireNonNull(currency, "currency");
        if (text == null) {
            throw new ApplicationException(MoneyErrorCode.INVALID_AMOUNT_FORMAT, "Amount is required.");
        }
        Matcher matcher = STRICT_DECIMAL.matcher(text);
        if (!matcher.matches()) {
            throw new ApplicationException(MoneyErrorCode.INVALID_AMOUNT_FORMAT,
                    "Amount must be a plain decimal number such as \"12.50\".");
        }
        String fraction = matcher.group(4);
        int fractionDigits = fraction == null ? 0 : fraction.length();
        if (fractionDigits > decimals) {
            throw new ApplicationException(MoneyErrorCode.TOO_MANY_DECIMALS,
                    currency + " allows at most " + decimals + " decimal(s).");
        }
        BigDecimal amount = new BigDecimal(text).setScale(decimals, RoundingMode.UNNECESSARY);
        return new Money(amount, currency);
    }

    /**
     * Rounding helper for a computation that does not land exactly on the minor unit (averages, divisions):
     * {@code HALF_EVEN}, applied once (BR-MON-05).
     */
    public static Money rounded(BigDecimal rawAmount, CurrencyCode currency, int decimals) {
        requireValidDecimals(decimals);
        Objects.requireNonNull(rawAmount, "rawAmount");
        Objects.requireNonNull(currency, "currency");
        return new Money(rawAmount.setScale(decimals, RoundingMode.HALF_EVEN), currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return currency;
    }

    /** Exact minor-unit count of this amount (the inverse of {@link #ofMinor(long, CurrencyCode, int)}). */
    public long minorUnits() {
        return amount.unscaledValue().longValueExact();
    }

    /** Plain decimal representation, e.g. {@code "12.50"} — the {@code amount} of the API JSON shape (§10.2). */
    public String toDecimalString() {
        return amount.toPlainString();
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /**
     * Splits this amount into {@code parts} shares (BR-MON-06): integer division of minor units, the remainder
     * distributed one minor unit at a time, first part first. The returned parts always sum to this amount.
     */
    public List<Money> split(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least 1, got: " + parts);
        }
        long total = minorUnits();
        long base = total / parts;
        long remainder = total % parts;
        long adjustment = Long.signum(remainder);
        long adjustedCount = Math.abs(remainder);

        List<Money> shares = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            long minor = base + (i < adjustedCount ? adjustment : 0);
            shares.add(Money.ofMinor(minor, currency, amount.scale()));
        }
        return List.copyOf(shares);
    }

    /**
     * Percentage of {@code whole} that this amount represents (BR-MON-09): computed from minor-unit integers,
     * rounded {@code HALF_EVEN} to one decimal.
     */
    public BigDecimal percentageOf(Money whole) {
        requireSameCurrency(whole);
        if (whole.isZero()) {
            throw new ArithmeticException("Cannot compute a percentage of a zero amount.");
        }
        return BigDecimal.valueOf(minorUnits())
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(whole.minorUnits()), 1, RoundingMode.HALF_EVEN);
    }

    /**
     * BR-MON-07: rejects an amount that is not strictly positive, or that exceeds {@code maximum} (the
     * per-currency maximum amount, supplied by the caller — this method hard-codes no limits table).
     */
    public void requirePositiveAtMost(Money maximum) {
        requireSameCurrency(maximum);
        if (!isPositive()) {
            throw new ApplicationException(MoneyErrorCode.AMOUNT_NOT_POSITIVE, "Amount must be strictly positive.");
        }
        if (compareTo(maximum) > 0) {
            throw new ApplicationException(MoneyErrorCode.AMOUNT_EXCEEDS_MAXIMUM,
                    "Amount exceeds the maximum of " + maximum.toDecimalString() + " " + maximum.currency() + ".");
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Money other
                && currency.equals(other.currency)
                && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return toDecimalString() + " " + currency.value();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new ApplicationException(MoneyErrorCode.CURRENCY_MISMATCH,
                    "Cannot combine or compare " + currency + " with " + other.currency + ".");
        }
    }

    private static void requireValidDecimals(int decimals) {
        if (decimals < 0 || decimals > 4) {
            throw new IllegalArgumentException("decimals must be between 0 and 4, got: " + decimals);
        }
    }
}
