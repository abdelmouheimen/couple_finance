package com.couplefinance.budget.application;

import java.time.LocalDate;

import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * Request to set the overall limit of the budget of the period starting at {@code periodStart}.
 *
 * @param ifMatch the raw {@code If-Match} header: required when the budget exists, absent to create it
 */
public record SetBudgetCommand(LocalDate periodStart, @Nullable Money overallLimit, @Nullable String ifMatch) {
}
