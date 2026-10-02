package com.couplefinance.budget.domain;

/** Race-free creation of a budget: the database decides which concurrent creator wins (BR-BUD-01). */
public interface BudgetStore {

    /**
     * Inserts {@code budget} unless the household already has a budget for the period
     * ({@code uq_budget_period}); a concurrent uncommitted creator makes this call wait for its outcome.
     *
     * @return {@code true} when the row was inserted, {@code false} when a budget already existed
     */
    boolean insertIfAbsent(Budget budget);
}
