/**
 * AI module (ai.md §3, ADR-004, ADR-005, ADR-008): provider-neutral ports in {@code ai.api}; provider adapters,
 * schema validation, consent guard and call log in {@code ai.infrastructure}. It has no access to business tables
 * and never persists financial data: callers receive untrusted, schema-validated transcriptions only.
 */
@ApplicationModule(displayName = "AI", allowedDependencies = {"shared", "identity :: api"})
package com.couplefinance.ai;

import org.springframework.modulith.ApplicationModule;
