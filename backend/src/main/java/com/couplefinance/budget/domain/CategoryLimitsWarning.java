package com.couplefinance.budget.domain;

import com.couplefinance.shared.money.Money;

/**
 * BR-BUD-04: the category limits add up to more than the overall limit. Informational, computed on read.
 *
 * @param categoryLimitsTotal sum of the category limits
 * @param overallLimit        the overall limit it exceeds
 */
public record CategoryLimitsWarning(Money categoryLimitsTotal, Money overallLimit) {
}
