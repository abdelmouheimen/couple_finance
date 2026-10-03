/**
 * Analytics module: deterministic, read-only aggregations over the household ledger (architecture.md section 3).
 *
 * <p>Current scope: the analytics of a budget period (Issues #30, #31) and its chart series (Issue #32). Every money figure comes from the
 * spending query ({@code expense.api}), the budget summary ({@code budget.api}) and the period calendar
 * ({@code household.api}); nothing is stored and no table is owned.
 */
@ApplicationModule(displayName = "Analytics", allowedDependencies = {"shared", "household :: api",
        "expense :: api", "budget :: api"})
package com.couplefinance.analytics;

import org.springframework.modulith.ApplicationModule;
