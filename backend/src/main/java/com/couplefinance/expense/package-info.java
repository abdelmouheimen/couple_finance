/**
 * Expense module: the household ledger (domain-model.md section 7) — expenses with their category items,
 * visibility, audit trail and events.
 *
 * <p>Current scope: creation of a manual {@code EXPENSE} or {@code REFUND} (Issues #15, #19) and the history
 * listing and search (Issue #16). Other modules reach it through
 * {@code expense.api} (events).
 */
@ApplicationModule(displayName = "Expense",
        allowedDependencies = {"shared", "identity :: api", "household :: api", "categorization :: api"})
package com.couplefinance.expense;

import org.springframework.modulith.ApplicationModule;
