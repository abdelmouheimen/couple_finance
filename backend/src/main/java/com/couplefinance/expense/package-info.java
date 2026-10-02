/**
 * Expense module: the household ledger (domain-model.md section 7) — expenses with their category items,
 * visibility, audit trail and events.
 *
 * <p>Current scope (Issue #15): creation of a manual {@code EXPENSE}. Other modules reach it through
 * {@code expense.api} (events).
 */
@ApplicationModule(displayName = "Expense",
        allowedDependencies = {"shared", "identity :: api", "household :: api", "categorization :: api"})
package com.couplefinance.expense;

import org.springframework.modulith.ApplicationModule;
