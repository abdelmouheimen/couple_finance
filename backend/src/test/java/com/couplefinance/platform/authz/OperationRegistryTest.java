package com.couplefinance.platform.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Meta-tests: the matrix fails the build for an unclassified, un-fixtured or stale operation. */
class OperationRegistryTest {

    private static final ApiOperation NEW_OPERATION =
            new ApiOperation("brandNewOperation", HttpMethod.GET, "/api/v1/brand-new", true);

    private static final OperationFixture.PrivacyProbe NOOP = (world, seeded) -> {
    };

    @Test
    void an_unclassified_operation_fails() {
        assertThat(OperationRegistry.problems(List.of(NEW_OPERATION), Map.of()))
                .singleElement().asString().contains("brandNewOperation").contains("not classified");
    }

    @Test
    void a_secured_operation_registered_without_fixture_call_fails() {
        Map<String, OperationFixture> registry = Map.of("brandNewOperation",
                new OperationFixture(OperationKind.HOUSEHOLD_SCOPED, null, false, NOOP, null));

        assertThat(OperationRegistry.problems(List.of(NEW_OPERATION), registry))
                .singleElement().asString().contains("no fixture call");
    }

    @Test
    void a_household_operation_needs_a_probe_or_a_justified_exemption() {
        Map<String, OperationFixture> neither = Map.of("brandNewOperation",
                OperationFixture.householdWithoutPersonalData(s -> Call.get("/x"), false, " "));
        Map<String, OperationFixture> both = Map.of("brandNewOperation",
                new OperationFixture(OperationKind.HOUSEHOLD_SCOPED, s -> Call.get("/x"), false, NOOP, "reason"));

        assertThat(OperationRegistry.problems(List.of(NEW_OPERATION), neither)).hasSize(1);
        assertThat(OperationRegistry.problems(List.of(NEW_OPERATION), both)).hasSize(1);
    }

    @Test
    void a_public_registration_must_match_the_contract_security() {
        Map<String, OperationFixture> registry = Map.of("brandNewOperation", OperationFixture.publicOperation());

        assertThat(OperationRegistry.problems(List.of(NEW_OPERATION), registry))
                .singleElement().asString().contains("requires authentication");
    }

    @Test
    void a_secured_registration_of_an_unsecured_operation_fails() {
        ApiOperation open = new ApiOperation("openOperation", HttpMethod.GET, "/api/v1/open", false);
        Map<String, OperationFixture> registry = Map.of("openOperation",
                OperationFixture.authenticated(s -> Call.get("/x")));

        assertThat(OperationRegistry.problems(List.of(open), registry))
                .singleElement().asString().contains("not secured in the contract");
    }

    @Test
    void a_stale_registry_entry_fails() {
        Map<String, OperationFixture> registry = new HashMap<>(OperationRegistry.defaults());

        assertThat(OperationRegistry.problems(List.of(), registry)).isNotEmpty()
                .allSatisfy(problem -> assertThat(problem).contains("matches no operation"));
    }

    @Test
    void the_contract_parser_reads_methods_and_effective_security() {
        Map<String, Object> root = Map.of(
                "security", List.of(Map.of("bearerAuth", List.of())),
                "paths", Map.of(
                        "/a", Map.of("get", Map.of("operationId", "secured"),
                                "post", Map.of("operationId", "open", "security", List.of()))));

        List<ApiOperation> operations = OpenApiOperations.parse(root);

        assertThat(operations).extracting(ApiOperation::operationId, ApiOperation::secured, ApiOperation::isWrite)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("secured", true, false),
                        org.assertj.core.groups.Tuple.tuple("open", false, true));
    }

    @Test
    void the_default_registry_covers_the_committed_contract() {
        List<ApiOperation> operations = OpenApiOperations.load(OpenApiOperations.defaultSpec());

        assertThat(OperationRegistry.problems(operations, OperationRegistry.defaults())).isEmpty();
    }
}
