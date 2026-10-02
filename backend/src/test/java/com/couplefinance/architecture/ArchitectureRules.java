package com.couplefinance.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;

import com.couplefinance.shared.money.Money;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;

/**
 * Architectural invariants enforced at build time, in addition to Spring Modulith's
 * {@code ApplicationModules.verify()} (see {@code ModularityTests}). Each rule is a factory so that the negative
 * tests ({@code ArchitectureRulesNegativeTest}) can run the very same rule against deliberately violating
 * fixtures and prove that it can fail. Rules that depend on the package layout take the root package; production
 * uses {@link #ROOT}.
 *
 * <p>Rules are never suppressed or filtered to make a build pass: a violation is fixed or raised for a human
 * decision.
 */
final class ArchitectureRules {

    static final String ROOT = "com.couplefinance";

    private ArchitectureRules() {}

    // ------------------------------------------------------ 1. scoped repositories (security.md 4.1, 4.4)

    /** Spring Data base interfaces that expose unscoped finders/deleters for every entity. */
    private static final List<Class<?>> UNSCOPED_BASE_INTERFACES = List.of(
            CrudRepository.class,
            PagingAndSortingRepository.class,
            ListCrudRepository.class,
            ListPagingAndSortingRepository.class,
            JpaRepository.class);

    private static final Set<String> UNSCOPED_METHOD_NAMES = Set.of(
            "findById", "findAll", "findAllById", "getById", "getReferenceById", "getOne",
            "deleteById", "deleteAllById", "deleteAll", "deleteAllInBatch", "existsById", "count");

    /**
     * The one documented exception (security.md 4.4): dedicated purge repositories, whose simple name ends with
     * {@code PurgeRepository}, may expose unscoped methods.
     */
    static final String PURGE_REPOSITORY_SUFFIX = "PurgeRepository";

    /**
     * Heuristic for "repository of household data": a Spring Data repository whose managed entity declares a
     * {@code householdId} attribute, directly or inside its embedded id (database.md: every household table has
     * {@code household_id}). The {@code Household} aggregate root itself (its id <em>is</em> the household) and
     * identity data are therefore not covered; PERSONAL ownership is enforced by the scoped queries themselves.
     */
    static final DescribedPredicate<JavaClass> HOUSEHOLD_DATA_REPOSITORY = describe(
            "Spring Data repositories of household-owned entities (not purge repositories)",
            repository -> repository.isInterface()
                    && repository.isAssignableTo(Repository.class)
                    && !repository.getSimpleName().endsWith(PURGE_REPOSITORY_SUFFIX)
                    && managedEntity(repository).map(ArchitectureRules::isHouseholdOwned).orElse(false));

    private static Optional<JavaClass> managedEntity(JavaClass repository) {
        // Walk generic super-interfaces until the parameterised Repository<T, ID> is found.
        for (JavaType type : repository.getInterfaces()) {
            Optional<JavaClass> found = entityTypeArgument(type);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<JavaClass> entityTypeArgument(JavaType type) {
        if (type instanceof JavaParameterizedType parameterized
                && type.toErasure().isEquivalentTo(Repository.class)
                && !parameterized.getActualTypeArguments().isEmpty()) {
            return Optional.of(parameterized.getActualTypeArguments().get(0).toErasure());
        }
        if (type instanceof JavaParameterizedType parameterized
                && !parameterized.getActualTypeArguments().isEmpty()
                && type.toErasure().isAssignableTo(Repository.class)) {
            // Intermediate generic interface such as CrudRepository<T, ID>: first argument is the entity.
            return Optional.of(parameterized.getActualTypeArguments().get(0).toErasure());
        }
        return Optional.empty();
    }

    private static boolean isHouseholdOwned(JavaClass entity) {
        return entity.getAllFields().stream().anyMatch(field -> field.getName().equals("householdId")
                || field.getRawType().getAllFields().stream().anyMatch(f -> f.getName().equals("householdId")
                        && field.isAnnotatedWith("jakarta.persistence.EmbeddedId")));
    }

    static ArchRule repositoriesOfHouseholdDataExposeOnlyScopedMethods() {
        return classes().that(HOUSEHOLD_DATA_REPOSITORY)
                .should(new ArchCondition<JavaClass>(
                        "not extend generic CRUD repositories nor declare unscoped finders (findById, findAll...)") {
                    @Override
                    public void check(JavaClass repository, ConditionEvents events) {
                        for (Class<?> base : UNSCOPED_BASE_INTERFACES) {
                            if (repository.isAssignableTo(base)) {
                                events.add(SimpleConditionEvent.violated(repository, repository.getName()
                                        + " extends " + base.getSimpleName() + " which exposes unscoped methods"));
                            }
                        }
                        for (var method : repository.getMethods()) {
                            if (UNSCOPED_METHOD_NAMES.contains(method.getName())) {
                                events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                        + " is an unscoped repository method; use a household-scoped one such as "
                                        + "findByIdAndHouseholdId"));
                            }
                        }
                    }
                })
                .allowEmptyShould(true)
                .because("security.md 4.1: repositories of household data expose only household-scoped methods; "
                        + "only dedicated *PurgeRepository classes may be unscoped (4.4)");
    }

    /**
     * The other half of the security.md 4.4 exception: unscoped purge repositories may only be used by dedicated
     * purge classes (simple name contains {@code Purge}), never by ordinary use cases or controllers.
     */
    static ArchRule purgeRepositoriesAreUsedOnlyByPurgeClasses() {
        return noClasses().that(describe("are not purge classes",
                        (JavaClass c) -> !c.getSimpleName().contains("Purge")))
                .should().dependOnClassesThat(describe("are purge repositories",
                        (JavaClass c) -> c.getSimpleName().endsWith(PURGE_REPOSITORY_SUFFIX)))
                .allowEmptyShould(true)
                .because("security.md 4.4: unscoped purge persistence is confined to dedicated purge classes");
    }

    // ------------------------------------------------------ 2. no floating point money (BR-MON-02)

    private static final Set<String> FLOATING_POINT_TYPES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    /** Heuristic: a class "handles money" when it is {@link Money} or references it. */
    static final DescribedPredicate<JavaClass> HANDLES_MONEY = describe(
            "classes that use Money",
            c -> c.isEquivalentTo(Money.class)
                    || c.getDirectDependenciesFromSelf().stream()
                            .anyMatch(d -> d.getTargetClass().isEquivalentTo(Money.class)));

    private static final Pattern MINOR_NAME = Pattern.compile(".*(Minor|_minor)$");

    /** Heuristic: a field maps a {@code *_minor} column (by name, or by {@code @Column(name = "..._minor")}). */
    static final DescribedPredicate<JavaField> MINOR_UNIT_FIELD = describe(
            "fields holding minor units (*Minor / *_minor)",
            f -> MINOR_NAME.matcher(f.getName()).matches()
                    || f.tryGetAnnotationOfType(Column.class)
                            .map(c -> c.name().endsWith("_minor"))
                            .orElse(false));

    private static boolean isFloatingPoint(JavaClass type) {
        return FLOATING_POINT_TYPES.contains(type.getName());
    }

    private static ArchCondition<JavaField> notFloatingPointField() {
        return new ArchCondition<>("not be float/double/Float/Double") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                if (isFloatingPoint(field.getRawType())) {
                    events.add(SimpleConditionEvent.violated(field,
                            field.getFullName() + " is floating point (monetary value)"));
                }
            }
        };
    }

    static ArchRule noFloatingPointFieldsInMoneyClasses() {
        return fields().that().areDeclaredInClassesThat(HANDLES_MONEY)
                .should(notFloatingPointField())
                .allowEmptyShould(true)
                .because("BR-MON-02 / CLAUDE.md 8: never float or double for monetary values");
    }

    static ArchRule noFloatingPointSignaturesInMoneyClasses() {
        return codeUnits().that().areDeclaredInClassesThat(HANDLES_MONEY)
                .should(new ArchCondition<JavaCodeUnit>("not return or accept float/double/Float/Double") {
                    @Override
                    public void check(JavaCodeUnit unit, ConditionEvents events) {
                        if (isFloatingPoint(unit.getRawReturnType())
                                || unit.getRawParameterTypes().stream()
                                        .anyMatch(ArchitectureRules::isFloatingPoint)) {
                            events.add(SimpleConditionEvent.violated(unit,
                                    unit.getFullName() + " uses floating point in a class handling Money"));
                        }
                    }
                })
                .allowEmptyShould(true)
                .because("BR-MON-02 / CLAUDE.md 8: never float or double for monetary values");
    }

    static ArchRule noBoxedFloatingPointInMoneyClasses() {
        return noClasses().that(HANDLES_MONEY)
                .should().dependOnClassesThat().areAssignableTo(Float.class)
                .orShould().dependOnClassesThat().areAssignableTo(Double.class)
                .allowEmptyShould(true)
                .because("BR-MON-02: no Float/Double (e.g. Double.parseDouble) in classes handling Money");
    }

    /**
     * Closes the main false negative of the signature/field heuristics: a primitive local created by
     * {@code amount.doubleValue()} leaves no trace in the bytecode dependencies. Known remaining limitation: purely
     * local arithmetic that never touches such a conversion is not detectable by ArchUnit.
     */
    static ArchRule noFloatingPointConversionsInMoneyClasses() {
        return noClasses().that(HANDLES_MONEY)
                .should().callMethodWhere(describe("convert a number to float/double", (JavaCall<?> call) ->
                        (call.getName().equals("doubleValue") || call.getName().equals("floatValue"))
                                && call.getTargetOwner().isAssignableTo(Number.class)))
                .allowEmptyShould(true)
                .because("BR-MON-02: no doubleValue()/floatValue() in classes handling Money");
    }

    static ArchRule noFloatingPointMinorUnitFields() {
        return fields().that(MINOR_UNIT_FIELD)
                .should(notFloatingPointField())
                .allowEmptyShould(true)
                .because("BR-MON-02: *_minor amounts are integral (BIGINT)");
    }

    static ArchRule noBigDecimalFromDouble() {
        return noClasses()
                .should().callConstructorWhere(describe("BigDecimal(double)", (JavaCall<?> call) ->
                        call.getTargetOwner().isEquivalentTo(BigDecimal.class)
                                && call.getTarget().getRawParameterTypes().stream()
                                        .anyMatch(t -> t.getName().equals("double"))))
                .allowEmptyShould(true)
                .because("CLAUDE.md 8: never new BigDecimal(double)");
    }

    // ------------------------------------------------------ 3. AI provider SDKs (BR-AI-04)

    /** Known LLM provider SDK / framework packages; extend when a new provider is approved. */
    static final String[] AI_PROVIDER_SDK_PACKAGES = {
        "com.openai..", "com.anthropic..", "com.google.genai..", "com.google.cloud.vertexai..",
        "com.google.ai.client..", "dev.langchain4j..", "org.springframework.ai..", "com.azure.ai..",
        "software.amazon.awssdk.services.bedrock..", "software.amazon.awssdk.services.bedrockruntime..",
        "com.mistral.."
    };

    static ArchRule aiProviderSdksOnlyInAiInfrastructure(String root) {
        return aiProviderSdksOnlyInAiInfrastructure(root, AI_PROVIDER_SDK_PACKAGES);
    }

    /** Overload taking the SDK packages so the negative test can use a package that exists on the classpath. */
    static ArchRule aiProviderSdksOnlyInAiInfrastructure(String root, String... sdkPackages) {
        return noClasses().that().resideOutsideOfPackage(root + ".ai.infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(sdkPackages)
                .allowEmptyShould(true)
                .because("BR-AI-04 / CLAUDE.md 12.4: vendor SDKs belong to ai.infrastructure only");
    }

    // ------------------------------------------------------ 4. controllers (CLAUDE.md 6.2)

    static ArchRule webDoesNotDependOnRepositoriesOrInfrastructure() {
        return noClasses().that().resideInAPackage("..web..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .orShould().dependOnClassesThat().areAssignableTo(Repository.class)
                .orShould().dependOnClassesThat().areAnnotatedWith(org.springframework.stereotype.Repository.class)
                .allowEmptyShould(true)
                .because("CLAUDE.md 6.2: controllers call one application use case, never repositories or "
                        + "infrastructure");
    }

    // ------------------------------------------------------ 5. module access only through api

    /** Module name of a package under {@code root}, or {@code null} when outside it. */
    private static String moduleOf(String root, String packageName) {
        String prefix = root + ".";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** First sub-package below the module ({@code api}, {@code domain}...), "" for the module root package. */
    private static String layerOf(String root, String packageName) {
        String rest = packageName.substring(root.length() + 1);
        int dot = rest.indexOf('.');
        if (dot < 0) {
            return "";
        }
        String afterModule = rest.substring(dot + 1);
        int next = afterModule.indexOf('.');
        return next < 0 ? afterModule : afterModule.substring(0, next);
    }

    /**
     * {@code shared} is the OPEN shared kernel (its package-info), reachable from every module, so it is exempt as a
     * target. Complements Modulith's verify() with an explicit, build-visible rule.
     */
    static ArchRule modulesAreAccessedOnlyThroughTheirApiPackage(String root) {
        return classes().that(describe("belong to a business module", (JavaClass c) -> {
                    String module = moduleOf(root, c.getPackageName());
                    return module != null && !module.equals("shared");
                }))
                .should(new ArchCondition<JavaClass>("only access other modules through their api package") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        String originModule = moduleOf(root, origin.getPackageName());
                        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                            JavaClass target = dependency.getTargetClass();
                            String targetModule = moduleOf(root, target.getPackageName());
                            if (targetModule == null || targetModule.equals("shared")
                                    || targetModule.equals(originModule)) {
                                continue;
                            }
                            if (!layerOf(root, target.getPackageName()).equals("api")) {
                                events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()
                                        + " (module " + originModule + " must use " + targetModule + ".api only)"));
                            }
                        }
                    }
                })
                .allowEmptyShould(true)
                .because("CLAUDE.md 6.1 / BR-AI-04: inter-module communication only through <module>.api or events");
    }

    private static boolean isJpaType(JavaClass type) {
        return type.isAnnotatedWith(Entity.class)
                || type.isAnnotatedWith(MappedSuperclass.class)
                || type.isAnnotatedWith(Embeddable.class);
    }

    private static boolean involvesJpaType(JavaType type) {
        return type.getAllInvolvedRawTypes().stream().anyMatch(ArchitectureRules::isJpaType);
    }

    /**
     * "Exposed" means: an entity (also as a generic argument) is the type of a field, a return type, or - in
     * {@code api} packages, whose methods are the module facade - a method parameter. A web DTO may still map
     * <em>from</em> an entity in a static factory parameter; that is mapping, not exposure.
     */
    static ArchRule jpaEntitiesAreNotExposedFromApiOrWeb() {
        return classes().that().resideInAnyPackage("..api..", "..web..")
                .should(new ArchCondition<JavaClass>("not expose JPA entities in fields, return types or api parameters") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        String pkg = type.getPackageName();
                        boolean api = pkg.endsWith(".api") || pkg.contains(".api.");
                        for (JavaField field : type.getFields()) {
                            if (involvesJpaType(field.getType())) {
                                events.add(SimpleConditionEvent.violated(field,
                                        field.getFullName() + " exposes a JPA entity"));
                            }
                        }
                        for (JavaCodeUnit unit : type.getCodeUnits()) {
                            if (involvesJpaType(unit.getReturnType())) {
                                events.add(SimpleConditionEvent.violated(unit,
                                        unit.getFullName() + " returns a JPA entity"));
                            }
                            if (api && unit.getParameterTypes().stream()
                                    .anyMatch(ArchitectureRules::involvesJpaType)) {
                                events.add(SimpleConditionEvent.violated(unit,
                                        unit.getFullName() + " accepts a JPA entity in a module api"));
                            }
                        }
                    }
                })
                .allowEmptyShould(true)
                .because("CLAUDE.md 10: JPA entities are never exposed through DTOs, events or module facades");
    }

    // ------------------------------------------------------ 6. no system clock (CLAUDE.md 7.1)

    private static final Set<String> TEMPORAL_TYPES = Set.of(
            "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
            "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.Year",
            "java.time.YearMonth", "java.time.MonthDay");

    private static boolean readsSystemClock(JavaCall<?> call) {
        String owner = call.getTargetOwner().getName();
        String name = call.getName();
        var parameters = call.getTarget().getRawParameterTypes();
        if (TEMPORAL_TYPES.contains(owner) && name.equals("now")) {
            // now(Clock) is deterministic; now() and now(ZoneId) read the system clock.
            return !(parameters.size() == 1 && parameters.get(0).isEquivalentTo(Clock.class));
        }
        if (owner.equals(Clock.class.getName())) {
            return name.startsWith("system"); // systemUTC(), systemDefaultZone(), system(zone)
        }
        return owner.equals(System.class.getName()) && (name.equals("currentTimeMillis") || name.equals("nanoTime"));
    }

    /** Allowed location: only {@code shared.time} (the Clock bean configuration) may create the system Clock. */
    static ArchRule noSystemClockOutsideClockConfiguration(String root) {
        return noClasses().that().resideInAPackage(root + "..").and().resideOutsideOfPackage(root + ".shared.time..")
                .should().callMethodWhere(describe("read the system clock", ArchitectureRules::readsSystemClock))
                .allowEmptyShould(true)
                .because("CLAUDE.md 7.1: inject Clock; only shared.time creates the system Clock");
    }
}
