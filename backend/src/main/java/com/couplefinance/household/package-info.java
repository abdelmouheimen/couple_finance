/**
 * Household module: the sharing unit — household, members, settings and lifecycle (domain-model.md §4).
 *
 * <p>Current scope (Issue #1): household creation.
 */
@ApplicationModule(displayName = "Household", allowedDependencies = {"shared", "identity :: api"})
package com.couplefinance.household;

import org.springframework.modulith.ApplicationModule;
