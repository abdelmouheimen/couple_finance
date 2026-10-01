/**
 * Shared kernel: cross-cutting building blocks used by every module (error model, security and API
 * configuration, clock, {@code Money}, typed ids; later {@code HouseholdContext}). Contains no business rules
 * and no persistence (architecture.md §4) — {@code Money}'s currency decimals are reached through the
 * {@link com.couplefinance.shared.money.CurrencyDecimals} port, implemented outside this module.
 *
 * <p>Declared {@code OPEN} so that all modules may depend on any of its packages.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
package com.couplefinance.shared;

import org.springframework.modulith.ApplicationModule;
