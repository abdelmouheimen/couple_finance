package com.couplefinance.budget.domain;

import java.util.List;
import java.util.UUID;

import com.couplefinance.shared.id.UserId;

/**
 * Persistence of the category limit lines of a budget ({@code budget.budget_category}). Every call is made while
 * the caller holds the budget row lock, in the caller's transaction.
 */
public interface BudgetCategoryLimits {

    /** A stored line: minor units in the budget's currency. */
    record Line(UUID categoryId, long limitMinor) {
    }

    /** The lines of a budget of the household, in a stable order (creation order). */
    List<Line> findByBudget(UUID householdId, UUID budgetId);

    void insert(Budget budget, UUID categoryId, long limitMinor, UserId actor);

    void updateLimit(Budget budget, UUID categoryId, long limitMinor, UserId actor);

    void delete(Budget budget, UUID categoryId);
}
