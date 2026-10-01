import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.couplefinance"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val springModulithVersion = "2.1.1"
val springdocVersion = "3.1.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:$springModulithVersion")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Property-based tests for Money (architecture.md §8: "jqwik ... for Money, expense items, parsers").
    testImplementation("net.jqwik:jqwik:1.9.3")
    // Architecture rules (architecture.md section 8); same version Spring Modulith already resolves transitively.
    testImplementation("com.tngtech.archunit:archunit:1.4.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

// Committed OpenAPI contract, shared with the mobile app (architecture.md §7).
val openApiSpec = layout.projectDirectory.file("../api/openapi.yaml").asFile

tasks.test {
    useJUnitPlatform()
    systemProperty("openapi.spec", openApiSpec.absolutePath)
    inputs.file(openApiSpec).optional().withPropertyName("openApiSpec")
}

tasks.register<Test>("updateOpenApi") {
    description = "Regenerates api/openapi.yaml from the running application."
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*OpenApiContractTest") }
    systemProperty("openapi.spec", openApiSpec.absolutePath)
    systemProperty("openapi.update", "true")
    outputs.upToDateWhen { false }
}

tasks.named<BootRun>("bootRun") {
    // Local development runs against infra/docker-compose.yml unless another profile is requested.
    environment("SPRING_PROFILES_ACTIVE", System.getenv("SPRING_PROFILES_ACTIVE") ?: "local")
}
