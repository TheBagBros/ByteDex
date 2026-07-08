pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // 0.9.0 crashes on Gradle 9.5.1 (JvmVendorSpec.IBM_SEMERU removed). Pin 0.8.0.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ByteDex"

// Lean capture build: only agent + proxy (skip :api/:app/:frontend, which trip a
// foojay/Gradle JvmVendorSpec.IBM_SEMERU incompatibility we don't need for capture).
// Switch back to the full include list once the OAuth+dashboard build is needed.
include(":agent", ":proxy")
