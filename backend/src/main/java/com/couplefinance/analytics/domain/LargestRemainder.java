package com.couplefinance.analytics.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * BR-ANA-05 / BR-MON-09: percentages of a total displayed to one decimal that sum to exactly 100.0, by the
 * largest-remainder method on integer minor units (no floating point). Each share is first floored to a tenth of a
 * percent; the tenths still missing to reach 1000 go, one each, to the largest remainders. Ties are broken by the
 * larger amount, then by the lower index, so the result is deterministic.
 */
public final class LargestRemainder {

    private static final BigInteger TENTHS_OF_100_PERCENT = BigInteger.valueOf(1000);

    private LargestRemainder() {
    }

    /**
     * @param amounts non-negative minor-unit amounts
     * @return one percentage (scale 1) per amount; all {@code 0.0} when the amounts sum to zero, otherwise they
     *         sum to exactly {@code 100.0}
     * @throws IllegalArgumentException when an amount is negative (BR-ANA-06: those are excluded beforehand)
     */
    public static List<BigDecimal> percentages(List<Long> amounts) {
        BigInteger total = BigInteger.ZERO;
        for (long amount : amounts) {
            if (amount < 0) {
                throw new IllegalArgumentException("A negative amount has no share of the total.");
            }
            total = total.add(BigInteger.valueOf(amount));
        }
        int n = amounts.size();
        long[] tenths = new long[n];
        if (total.signum() == 0) {
            return toPercentages(tenths);
        }
        BigInteger[] remainders = new BigInteger[n];
        long allocated = 0;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = BigInteger.valueOf(amounts.get(i)).multiply(TENTHS_OF_100_PERCENT)
                    .divideAndRemainder(total);
            tenths[i] = qr[0].longValueExact();
            remainders[i] = qr[1];
            allocated += tenths[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
                .thenComparing(Comparator.<Integer, Long>comparing(amounts::get).reversed())
                .thenComparing(Comparator.naturalOrder()));
        for (int k = 0; k < 1000 - allocated; k++) {
            tenths[order.get(k)]++;
        }
        return toPercentages(tenths);
    }

    private static List<BigDecimal> toPercentages(long[] tenths) {
        List<BigDecimal> result = new ArrayList<>();
        for (long t : tenths) {
            result.add(BigDecimal.valueOf(t, 1));
        }
        return result;
    }
}
