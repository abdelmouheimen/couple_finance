/**
 * Household module: the sharing unit — household, members, settings and lifecycle (domain-model.md §4).
 *
 * <p>Current scope (Issues #1, #8, #9): household creation, budget period calendar, current household and the
 * {@code HouseholdContext} facade.
 */
@ApplicationModule(displayName = "Household", allowedDependencies = {"shared", "identity :: api"})
package com.couplefinance.household;

import org.springframework.modulith.ApplicationModule;
