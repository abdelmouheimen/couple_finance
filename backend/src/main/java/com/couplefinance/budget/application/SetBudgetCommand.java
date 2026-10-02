package com.couplefinance.budget.application;

import java.time.LocalDate;
import java.util.List;

import com.couplefinance.budget.domain.CategoryLimit;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * Request to set the limits of the budget of the period starting at {@code periodStart}.
 *
 * @param overallLimit   the overall limit; absent clears it (allowed only while category limits remain)
 * @param categoryLimits the complete set of category limits; {@code null} leaves the existing ones untouched, an
 *                       empty list removes them all
 * @param ifMatch        the raw {@code If-Match} header: required when the budget exists, absent to create it
 */
public record SetBudgetCommand(LocalDate periodStart, @Nullable Money overallLimit,
        @Nullable List<CategoryLimit> categoryLimits, @Nullable String ifMatch) {
}
