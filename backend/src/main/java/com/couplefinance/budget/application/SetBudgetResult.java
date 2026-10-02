package com.couplefinance.budget.application;

/** Outcome of setting a budget: the budget and whether this call created it (201) or updated it (200). */
public record SetBudgetResult(BudgetView budget, boolean created) {
}
