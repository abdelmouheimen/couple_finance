/**
 * Budget module: the spending limits a household agreed on for one budget period (domain-model.md section 8).
 *
 * <p>Current scope: the overall limit of a period (Issue #24) and its consumption, computed on read through the
 * expense spending query (Issue #28). Category limits (#25) come later.
 * Other modules reach it through {@code budget.api} (events).
 */
@ApplicationModule(displayName = "Budget", allowedDependencies = {"shared", "household :: api", "expense :: api"})
package com.couplefinance.budget;

import org.springframework.modulith.ApplicationModule;
