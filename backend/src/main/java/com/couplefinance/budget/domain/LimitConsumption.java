package com.couplefinance.budget.domain;

import java.math.BigDecimal;
import java.util.Objects;

import com.couplefinance.shared.money.Money;

/**
 * Consumption of one limit (BR-BUD-03, BR-BUD-05, BR-BUD-06): computed on read from the household spending of the
 * period, never stored. All figures derive from minor-unit integers; no floating point.
 *
 * @param limit      the limit, strictly positive
 * @param consumed   net household spending in the period; may be negative when refunds exceed expenses
 * @param remaining  {@code limit - consumed}; negative once exceeded (BR-BUD-05)
 * @param percentage {@code consumed / limit} in percent, {@code HALF_EVEN}, one decimal (BR-MON-09)
 * @param status     BR-BUD-06 status
 */
public record LimitConsumption(Money limit, Money consumed, Money remaining, BigDecimal percentage,
        BudgetStatus status) {

    /**
     * The status is decided on the exact integer minor units, not on the rounded percentage: a limit exceeded by
     * one minor unit is {@code EXCEEDED}, whatever the displayed one-decimal percentage.
     */
    public static LimitConsumption of(Money limit, Money consumed) {
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(consumed, "consumed");
        if (!limit.isPositive()) {
            throw new IllegalArgumentException("A limit is strictly positive.");
        }
        return new LimitConsumption(limit, consumed, limit.subtract(consumed), consumed.percentageOf(limit),
                statusOf(limit.minorUnits(), consumed.minorUnits()));
    }

    static BudgetStatus statusOf(long limitMinor, long consumedMinor) {
        // consumed/limit > 100%  <=>  consumed > limit;  consumed/limit >= 80%  <=>  5*consumed >= 4*limit.
        if (consumedMinor > limitMinor) {
            return BudgetStatus.EXCEEDED;
        }
        if (Math.multiplyExact(5L, consumedMinor) >= Math.multiplyExact(4L, limitMinor)) {
            return BudgetStatus.WARNING;
        }
        return BudgetStatus.ON_TRACK;
    }
}
