/**
 * Budget module: the spending limits a household agreed on for one budget period (domain-model.md section 8).
 *
 * <p>Current scope (Issue #24): the overall limit of a period. Category limits (#25) and consumption come later.
 * Other modules reach it through {@code budget.api} (events).
 */
@ApplicationModule(displayName = "Budget", allowedDependencies = {"shared", "household :: api"})
package com.couplefinance.budget;

import org.springframework.modulith.ApplicationModule;
