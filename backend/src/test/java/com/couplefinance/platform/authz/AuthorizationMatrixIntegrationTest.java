package com.couplefinance.platform.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authorization test matrices generated from {@code api/openapi.yaml} (security.md section 4.1 point 6 and section
 * 4.2; Issue #33). Every operation of the contract must be registered in {@link OperationRegistry}; the matrix then
 * runs for each operation:
 * <ul>
 * <li>unauthenticated call: 401 AUTHENTICATION_REQUIRED;</li>
 * <li>household B user on household A resources: 404 for by-id operations, never any data of A (BR-HH-03);</li>
 * <li>user without household: 404 HOUSEHOLD_NOT_FOUND;</li>
 * <li>write on a dissolved household: 403 HOUSEHOLD_READ_ONLY (BR-HH-10);</li>
 * <li>partner privacy probe on PERSONAL data (BR-EXP-07).</li>
 * </ul>
 */
@IntegrationTest
class AuthorizationMatrixIntegrationTest {

    static final List<ApiOperation> OPERATIONS = OpenApiOperations.load(OpenApiOperations.defaultSpec());
    static final Map<String, OperationFixture> REGISTRY = OperationRegistry.defaults();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    static Stream<ApiOperation> authenticated() {
        return OPERATIONS.stream().filter(o -> kind(o) != OperationKind.PUBLIC);
    }

    static Stream<ApiOperation> householdScoped() {
        return OPERATIONS.stream().filter(o -> kind(o) == OperationKind.HOUSEHOLD_SCOPED);
    }

    static Stream<ApiOperation> householdScopedWrites() {
        return householdScoped().filter(ApiOperation::isWrite);
    }

    static Stream<ApiOperation> householdScopedById() {
        return householdScoped().filter(o -> REGISTRY.get(o.operationId()).addressesById());
    }

    static Stream<ApiOperation> householdScopedNotById() {
        return householdScoped().filter(o -> !REGISTRY.get(o.operationId()).addressesById());
    }

    static Stream<ApiOperation> probed() {
        return householdScoped().filter(o -> REGISTRY.get(o.operationId()).privacyProbe() != null);
    }

    private static OperationKind kind(ApiOperation operation) {
        OperationFixture fixture = REGISTRY.get(operation.operationId());
        return fixture == null ? OperationKind.PUBLIC : fixture.kind();
    }

    private AuthzWorld world() {
        return new AuthzWorld(mvc, tokens, users, jdbc, json);
    }

    private static Call call(ApiOperation operation, Scenario scenario) {
        return REGISTRY.get(operation.operationId()).call().apply(scenario);
    }

    // ------------------------------------------------------------------ completeness (deny by default)

    @Test
    void every_operation_of_the_contract_is_classified_and_fixtured() {
        assertThat(OPERATIONS).isNotEmpty();
        assertThat(OperationRegistry.problems(OPERATIONS, REGISTRY)).isEmpty();
    }

    @Test
    void the_coverage_report_lists_every_operation_and_its_checks() throws IOException {
        String report = coverageReport();
        Path file = Path.of("build", "reports", "authz-matrix", "coverage.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, report);
        for (ApiOperation operation : OPERATIONS) {
            assertThat(report).contains(operation.operationId());
        }
    }

    static String coverageReport() {
        StringBuilder report = new StringBuilder("operation | kind | unauthenticated | cross-household | "
                + "no household | dissolved write | privacy\n");
        for (ApiOperation operation : OPERATIONS) {
            OperationFixture f = REGISTRY.get(operation.operationId());
            OperationKind kind = f == null ? null : f.kind();
            boolean secured = kind != null && kind != OperationKind.PUBLIC;
            boolean scoped = kind == OperationKind.HOUSEHOLD_SCOPED;
            report.append(operation).append(" | ").append(kind == null ? "UNCLASSIFIED" : kind).append(" | ")
                    .append(secured ? "401" : "n/a (public)").append(" | ")
                    .append(scoped ? (f.addressesById() ? "404 by id" : "no foreign data") : "n/a").append(" | ")
                    .append(scoped ? "404" : "n/a").append(" | ")
                    .append(scoped && operation.isWrite() ? "403 read-only" : "n/a").append(" | ")
                    .append(!scoped ? "n/a" : f.privacyProbe() != null ? "probe"
                            : "exempt: " + f.privacyExemption()).append('\n');
        }
        return report.toString();
    }

    // ------------------------------------------------------------------ 401

    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticated")
    void unauthenticated_call_is_rejected_with_AUTHENTICATION_REQUIRED(ApiOperation operation) {
        SeededHousehold seeded = world().seedHousehold();
        Call call = call(operation, new Scenario(seeded.owner(), seeded));

        MvcTestResult result = world().send(null, call);

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code")
                .isEqualTo("AUTHENTICATION_REQUIRED");
    }

    // ------------------------------------------------------------------ BR-HH-03 isolation

    @ParameterizedTest(name = "{0}")
    @MethodSource("householdScopedById")
    void BR_HH_03_a_user_of_household_B_gets_404_on_household_A_resources(ApiOperation operation) {
        AuthzWorld world = world();
        SeededHousehold a = world.seedHousehold();
        SeededHousehold b = world.seedHousehold();

        String before = world.snapshot(a);
        Call call = call(operation, new Scenario(b.owner(), a));
        MvcTestResult result = world.send(b.owner(), call);

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertNoLeak(a, AuthzWorld.text(result), call);
        assertThat(world.snapshot(a)).as("household A data must be untouched").isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("householdScopedNotById")
    void BR_HH_03_operations_without_resource_id_expose_nothing_of_household_A(ApiOperation operation) {
        AuthzWorld world = world();
        SeededHousehold a = world.seedHousehold();
        SeededHousehold b = world.seedHousehold();

        Call call = call(operation, new Scenario(b.owner(), a));
        MvcTestResult result = world.send(b.owner(), call);

        assertThat(result.getResponse().getStatus()).isLessThan(500);
        assertNoLeak(a, AuthzWorld.text(result), call);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("householdScoped")
    void BR_HH_03_a_user_without_household_gets_404_HOUSEHOLD_NOT_FOUND(ApiOperation operation) {
        AuthzWorld world = world();
        SeededHousehold a = world.seedHousehold();
        UUID loner = world.newUser();

        MvcTestResult result = world.send(loner, call(operation, new Scenario(loner, a)));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("HOUSEHOLD_NOT_FOUND");
    }

    private static void assertNoLeak(SeededHousehold a, String responseText, Call call) {
        String text = responseText.replace(call.uri(), "");
        for (String marker : a.householdMarkers()) {
            assertThat(text).as("response must not contain " + marker).doesNotContain(marker);
        }
    }

    // ------------------------------------------------------------------ BR-HH-10 dissolved households

    @ParameterizedTest(name = "{0}")
    @MethodSource("householdScopedWrites")
    void BR_HH_10_a_write_on_a_dissolved_household_is_rejected(ApiOperation operation) {
        AuthzWorld world = world();
        SeededHousehold a = world.seedHousehold();
        world.dissolve(a);

        MvcTestResult result = world.send(a.owner(), call(operation, new Scenario(a.owner(), a)));

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code")
                .isEqualTo("HOUSEHOLD_READ_ONLY");
    }

    // ------------------------------------------------------------------ BR-EXP-07 privacy

    @ParameterizedTest(name = "{0}")
    @MethodSource("probed")
    void BR_EXP_07_the_partner_never_observes_the_owners_personal_data(ApiOperation operation) {
        AuthzWorld world = world();
        SeededHousehold seeded = world.seedHousehold();

        REGISTRY.get(operation.operationId()).privacyProbe().verify(world, seeded);
    }
}
