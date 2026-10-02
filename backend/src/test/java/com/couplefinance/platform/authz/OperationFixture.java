package com.couplefinance.platform.authz;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Classification and fixtures of one operation.
 *
 * @param kind             public / authenticated-only / household-scoped
 * @param call             builds the request for a caller against a seeded household ({@code null} only for PUBLIC)
 * @param addressesById    the request targets one existing resource by id (cross-household calls must be 404)
 * @param privacyProbe     asserts that the partner of a PERSONAL data owner observes nothing (household-scoped only)
 * @param privacyExemption justification when the operation cannot expose PERSONAL data (household-scoped only)
 */
public record OperationFixture(OperationKind kind, @Nullable Function<Scenario, Call> call, boolean addressesById,
        @Nullable PrivacyProbe privacyProbe, @Nullable String privacyExemption) {

    public static OperationFixture publicOperation() {
        return new OperationFixture(OperationKind.PUBLIC, null, false, null, null);
    }

    public static OperationFixture authenticated(Function<Scenario, Call> call) {
        return new OperationFixture(OperationKind.AUTHENTICATED_ONLY, call, false, null, null);
    }

    public static OperationFixture household(Function<Scenario, Call> call, boolean addressesById,
            PrivacyProbe probe) {
        return new OperationFixture(OperationKind.HOUSEHOLD_SCOPED, call, addressesById, probe, null);
    }

    public static OperationFixture householdWithoutPersonalData(Function<Scenario, Call> call,
            boolean addressesById, String reason) {
        return new OperationFixture(OperationKind.HOUSEHOLD_SCOPED, call, addressesById, null, reason);
    }

    /** Asserts, with the seeded data, that the partner never observes the owner's PERSONAL data. */
    @FunctionalInterface
    public interface PrivacyProbe {
        void verify(AuthzWorld world, SeededHousehold seeded);
    }
}
