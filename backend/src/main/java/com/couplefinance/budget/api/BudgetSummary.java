package com.couplefinance.budget.api;

import java.math.BigDecimal;
import java.util.Objects;

import com.couplefinance.shared.money.Money;

/**
 * The overall limit of a budget period and how much of it the household spending consumed (BR-BUD-03, BR-BUD-05,
 * BR-BUD-06). Derived from household spending only (BR-SCP-01); PERSONAL spending is never part of it.
 *
 * @param remaining  {@code limit - consumed}; negative once exceeded
 * @param percentage consumed / limit in percent, one decimal (BR-MON-09)
 */
public record BudgetSummary(Money limit, Money consumed, Money remaining, BigDecimal percentage, Status status) {

    /** BR-BUD-06 status. */
    public enum Status { ON_TRACK, WARNING, EXCEEDED }

    public BudgetSummary {
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(consumed, "consumed");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(percentage, "percentage");
        Objects.requireNonNull(status, "status");
    }
}
