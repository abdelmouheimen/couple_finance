package com.couplefinance.budget.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.couplefinance.budget.domain.Budget;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/** A budget as seen by the web layer: amounts as {@link Money} in the household currency. */
public record BudgetView(UUID id, LocalDate periodStart, LocalDate periodEnd, @Nullable Money overallLimit,
        UUID createdBy, Instant createdAt, UUID updatedBy, Instant updatedAt, long version) {

    static BudgetView of(Budget budget, int decimals) {
        Long limit = budget.overallLimitMinor();
        return new BudgetView(budget.id(), budget.periodStart(), budget.periodEnd(),
                limit == null ? null : Money.ofMinor(limit, budget.currency(), decimals), budget.createdBy(),
                budget.createdAt(), budget.updatedBy(), budget.updatedAt(), budget.version());
    }
}
