package com.couplefinance.architecture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Runs the architecture rules on the production classes (they run in {@code ./gradlew build}). Complements
 * {@code ModularityTests} (Spring Modulith). Never suppress a rule or exclude a class to make a failure go away:
 * fix the code or raise a decision (security.md 4.1, CLAUDE.md 6.1/6.2/7.1/8/10/12.4).
 */
class ArchitectureTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ArchitectureRules.ROOT);
    }

    @Test
    void production_classes_are_imported() {
        // Guards against rules silently passing on an empty import (allowEmptyShould is enabled on the rules).
        org.assertj.core.api.Assertions.assertThat(production.size()).isGreaterThan(50);
        org.assertj.core.api.Assertions.assertThat(production.contain(com.couplefinance.shared.money.Money.class))
                .isTrue();
    }

    @Test
    void security_4_1_repositories_of_household_data_expose_only_scoped_methods() {
        ArchitectureRules.repositoriesOfHouseholdDataExposeOnlyScopedMethods().check(production);
    }

    @Test
    void BR_MON_02_no_floating_point_fields_in_money_classes() {
        ArchitectureRules.noFloatingPointFieldsInMoneyClasses().check(production);
    }

    @Test
    void BR_MON_02_no_floating_point_signatures_in_money_classes() {
        ArchitectureRules.noFloatingPointSignaturesInMoneyClasses().check(production);
    }

    @Test
    void BR_MON_02_no_boxed_floating_point_in_money_classes() {
        ArchitectureRules.noBoxedFloatingPointInMoneyClasses().check(production);
    }

    @Test
    void BR_MON_02_no_floating_point_conversions_in_money_classes() {
        ArchitectureRules.noFloatingPointConversionsInMoneyClasses().check(production);
    }

    @Test
    void BR_MON_02_minor_unit_fields_are_not_floating_point() {
        ArchitectureRules.noFloatingPointMinorUnitFields().check(production);
    }

    @Test
    void BR_MON_02_no_BigDecimal_from_double() {
        ArchitectureRules.noBigDecimalFromDouble().check(production);
    }

    @Test
    void BR_AI_04_ai_provider_sdks_only_in_ai_infrastructure() {
        ArchitectureRules.aiProviderSdksOnlyInAiInfrastructure(ArchitectureRules.ROOT).check(production);
    }

    @Test
    void web_does_not_depend_on_repositories_or_infrastructure() {
        ArchitectureRules.webDoesNotDependOnRepositoriesOrInfrastructure().check(production);
    }

    @Test
    void modules_are_accessed_only_through_their_api_package() {
        ArchitectureRules.modulesAreAccessedOnlyThroughTheirApiPackage(ArchitectureRules.ROOT).check(production);
    }

    @Test
    void jpa_entities_are_not_exposed_from_api_or_web() {
        ArchitectureRules.jpaEntitiesAreNotExposedFromApiOrWeb().check(production);
    }

    @Test
    void no_system_clock_outside_clock_configuration() {
        ArchitectureRules.noSystemClockOutsideClockConfiguration(ArchitectureRules.ROOT).check(production);
    }
}
