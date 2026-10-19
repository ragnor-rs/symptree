plugins {
    base
    kotlin("jvm") apply false
    id("com.gradleup.shadow") apply false
    id("io.gitlab.arturbosch.detekt")
    id("com.autonomousapps.dependency-analysis")
}

group = "com.symptree"
version = "0.3.0"

// admin-panel is a Node/Vite SPA (FR-XKH348) — it owns its own build.gradle.kts
// (base + Exec tasks) and must NOT get the Kotlin/detekt/dep-analysis plugins.
configure(subprojects.filter { it.name != "admin-panel" }) {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "com.autonomousapps.dependency-analysis")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("detekt.yml"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
