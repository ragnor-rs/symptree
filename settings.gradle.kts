pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.2.20"
        kotlin("plugin.serialization") version "2.2.20"
        id("io.gitlab.arturbosch.detekt") version "1.23.8"
        id("com.autonomousapps.dependency-analysis") version "2.7.0"
        id("com.gradleup.shadow") version "9.2.2"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "symptree"

include("core")
include("messaging")
include("admin-panel") // Node/Vite SPA — driven via Exec tasks, not the Kotlin plugins (FR-XKH348)
