package com.couplefinance.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Proves that every rule can fail: each rule is run against deliberately violating fixtures (test sources under the
 * {@code archfixtures} root, outside the application's component/entity scan) and against compliant fixtures that
 * it must accept.
 */
class ArchitectureRulesNegativeTest {

    private static final String FIXTURES = "archfixtures";

    private static JavaClasses fixtures(String pkg) {
        return new ClassFileImporter().importPackages(FIXTURES + "." + pkg);
    }

    private static void assertViolates(ArchRule rule, JavaClasses classes, String... fragments) {
        var thrown = assertThatThrownBy(() -> rule.check(classes)).isInstanceOf(AssertionError.class);
        for (String fragment : fragments) {
            thrown.hasMessageContaining(fragment);
        }
    }

    private static void assertAccepts(ArchRule rule, JavaClasses classes) {
        assertThatCode(() -> rule.check(classes)).doesNotThrowAnyException();
    }

    // --- 1. scoped repositories

    @Test
    void scoped_repositories_rule_flags_crud_and_unscoped_finders() {
        ArchRule rule = ArchitectureRules.repositoriesOfHouseholdDataExposeOnlyScopedMethods();
        assertViolates(rule, fixtures("repo.bad"),
                "CrudThingRepository extends CrudRepository",
                "FindByIdThingRepository.findById");
    }

    @Test
    void scoped_repositories_rule_accepts_scoped_global_and_purge_repositories() {
        JavaClasses good = fixtures("repo.good");
        assertAccepts(ArchitectureRules.repositoriesOfHouseholdDataExposeOnlyScopedMethods(), good);
        // The purge exception is real, not an artefact of an empty selection: the repository is recognised...
        assertThat(good.stream().filter(ArchitectureRules.HOUSEHOLD_DATA_REPOSITORY::test).map(c -> c.getSimpleName()))
                .containsExactly("ScopedThingRepository");
    }

    @Test
    void purge_repository_rule_flags_use_outside_purge_classes_and_accepts_purge_classes() {
        assertViolates(ArchitectureRules.purgeRepositoriesAreUsedOnlyByPurgeClasses(), fixtures("purge.bad"),
                "ThingService");
        assertAccepts(ArchitectureRules.purgeRepositoriesAreUsedOnlyByPurgeClasses(), fixtures("purge.good"));
    }

    // --- 2. money

    @Test
    void BR_MON_02_rule_flags_double_field_in_money_class() {
        assertViolates(ArchitectureRules.noFloatingPointFieldsInMoneyClasses(), fixtures("money.bad"),
                "DoubleFieldWithMoney.rate");
    }

    @Test
    void BR_MON_02_rule_flags_floating_point_parameter_in_money_class() {
        assertViolates(ArchitectureRules.noFloatingPointSignaturesInMoneyClasses(), fixtures("money.bad"),
                "DoubleParameterWithMoney.scale");
    }

    @Test
    void BR_MON_02_rule_flags_boxed_floating_point_use_in_money_class() {
        assertViolates(ArchitectureRules.noBoxedFloatingPointInMoneyClasses(), fixtures("money.bad"),
                "BoxedDoubleWithMoney");
    }

    @Test
    void BR_MON_02_rule_flags_floating_point_minor_unit_field() {
        assertViolates(ArchitectureRules.noFloatingPointMinorUnitFields(), fixtures("money.bad"),
                "FloatingMinorField.amountMinor");
    }

    @Test
    void BR_MON_02_rule_flags_double_conversion_in_money_class() {
        assertViolates(ArchitectureRules.noFloatingPointConversionsInMoneyClasses(), fixtures("money.bad"),
                "DoubleConversionWithMoney");
    }

    @Test
    void BR_MON_02_rule_flags_BigDecimal_from_double() {
        assertViolates(ArchitectureRules.noBigDecimalFromDouble(), fixtures("money.bad"), "BigDecimalFromDouble");
    }

    @Test
    void BR_MON_02_rules_accept_integral_minor_units_and_decimal_strings() {
        JavaClasses good = fixtures("money.good");
        assertAccepts(ArchitectureRules.noFloatingPointFieldsInMoneyClasses(), good);
        assertAccepts(ArchitectureRules.noFloatingPointSignaturesInMoneyClasses(), good);
        assertAccepts(ArchitectureRules.noBoxedFloatingPointInMoneyClasses(), good);
        assertAccepts(ArchitectureRules.noFloatingPointMinorUnitFields(), good);
        assertAccepts(ArchitectureRules.noBigDecimalFromDouble(), good);
        assertAccepts(ArchitectureRules.noFloatingPointConversionsInMoneyClasses(), good);
        assertThat(good.stream().filter(ArchitectureRules.HANDLES_MONEY::test)).isNotEmpty();
    }

    // --- 3. AI SDKs (a stand-in "SDK" package that exists on the test classpath is used)

    @Test
    void BR_AI_04_rule_flags_sdk_use_outside_ai_infrastructure_and_accepts_it_inside() {
        ArchRule rule = ArchitectureRules.aiProviderSdksOnlyInAiInfrastructure(FIXTURES, "org.springframework.data..");
        assertViolates(rule, fixtures("ai.bad"), "UsesSdk");
        assertAccepts(rule, fixtures("ai.infrastructure"));
    }

    @Test
    void BR_AI_04_default_sdk_list_covers_the_approved_vendor_packages() {
        assertThat(ArchitectureRules.AI_PROVIDER_SDK_PACKAGES).contains("com.openai..", "com.anthropic..");
    }

    // --- 4. controllers

    @Test
    void web_rule_flags_repository_and_infrastructure_dependencies() {
        assertViolates(ArchitectureRules.webDoesNotDependOnRepositoriesOrInfrastructure(), fixtures("web.bad"),
                "RepositoryController", "InfrastructureController");
    }

    @Test
    void web_rule_accepts_controller_calling_application_use_case() {
        assertAccepts(ArchitectureRules.webDoesNotDependOnRepositoriesOrInfrastructure(), fixtures("web.good"));
    }

    // --- 5. module access / entities

    @Test
    void module_rule_flags_access_to_another_modules_internals() {
        assertViolates(ArchitectureRules.modulesAreAccessedOnlyThroughTheirApiPackage(FIXTURES + ".modules.bad"),
                fixtures("modules.bad"), "ReachesIntoInternals", "moduleb.api only");
    }

    @Test
    void module_rule_accepts_access_through_api() {
        assertAccepts(ArchitectureRules.modulesAreAccessedOnlyThroughTheirApiPackage(FIXTURES + ".modules.good"),
                fixtures("modules.good"));
    }

    @Test
    void entity_rule_flags_entity_exposed_from_api_and_accepts_dto() {
        assertViolates(ArchitectureRules.jpaEntitiesAreNotExposedFromApiOrWeb(), fixtures("entity.bad"),
                "ExposesEntity.find() returns a JPA entity");
        assertAccepts(ArchitectureRules.jpaEntitiesAreNotExposedFromApiOrWeb(), fixtures("entity.good"));
    }

    // --- 6. clock

    @Test
    void clock_rule_flags_system_clock_reads() {
        assertViolates(ArchitectureRules.noSystemClockOutsideClockConfiguration(FIXTURES), fixtures("clock.bad"),
                "ReadsSystemClock", "CreatesSystemClock");
    }

    @Test
    void clock_rule_accepts_injected_clock_and_allows_shared_time_configuration() {
        ArchRule rule = ArchitectureRules.noSystemClockOutsideClockConfiguration(FIXTURES);
        assertAccepts(rule, fixtures("clock.good"));
        assertAccepts(rule, fixtures("shared.time"));
    }
}
