package com.couplefinance;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Module boundaries are part of the build (ADR-001, CLAUDE.md §2). Never weaken this test to make it pass. */
class ModularityTests {

    @Test
    void module_boundaries_are_respected() {
        ApplicationModules.of(CoupleFinanceApplication.class).verify();
    }
}
