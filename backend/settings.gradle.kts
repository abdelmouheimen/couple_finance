plugins {
    // Lets Gradle download a JDK 25 when none is installed locally (toolchain auto-provisioning).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "couplefinance-backend"
