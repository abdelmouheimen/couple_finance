package com.couplefinance.budget.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.budget.domain.Budget;
import com.couplefinance.budget.domain.CategoryLimit;
import com.couplefinance.budget.domain.CategoryLimitsWarning;
import com.couplefinance.budget.domain.LimitConsumption;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;

/**
 * A budget as seen by the web layer: amounts as {@link Money} in the household currency. The consumption is only
 * filled by a read ({@link BudgetService#get}); it is {@code null} after a write or without an overall limit. The
 * per-category consumption (BR-BUD-03) follows the same rule: keyed by category id, empty after a write. The
 * warning (BR-BUD-04) is computed on every view and never stored.
 */
public record BudgetView(UUID id, LocalDate periodStart, LocalDate periodEnd, @Nullable Money overallLimit,
        @Nullable LimitConsumption overallConsumption, List<CategoryLimit> categoryLimits,
        Map<UUID, LimitConsumption> categoryConsumption,
        @Nullable CategoryLimitsWarning categoryLimitsWarning, UUID createdBy, Instant createdAt, UUID updatedBy,
        Instant updatedAt, long version, @Nullable UUID copiedFromBudgetId) {

    static BudgetView of(Budget budget, int decimals, @Nullable LimitConsumption overallConsumption,
            List<CategoryLimit> categoryLimits, Map<UUID, LimitConsumption> categoryConsumption,
            @Nullable CategoryLimitsWarning warning) {
        Long limit = budget.overallLimitMinor();
        return new BudgetView(budget.id(), budget.periodStart(), budget.periodEnd(),
                limit == null ? null : Money.ofMinor(limit, budget.currency(), decimals), overallConsumption,
                categoryLimits, Map.copyOf(categoryConsumption), warning, budget.createdBy(), budget.createdAt(),
                budget.updatedBy(), budget.updatedAt(), budget.version(), budget.copiedFromBudgetId());
    }
}
