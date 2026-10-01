/**
 * Shared kernel: cross-cutting building blocks used by every module (error model, security and API
 * configuration, clock; later {@code Money}, typed ids, {@code HouseholdContext}). Contains no business rules
 * and no persistence (architecture.md §4).
 *
 * <p>Declared {@code OPEN} so that all modules may depend on any of its packages.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
package com.couplefinance.shared;

import org.springframework.modulith.ApplicationModule;
