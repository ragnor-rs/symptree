// admin-panel — the clinic-admin Vite + React + TS SPA (FR-XKH348).
// This module does NOT use Kotlin/JVM plugins; it drives the Node toolchain via
// Exec tasks wired into the standard Gradle lifecycle, mirroring the MMP
// `frontend` module. `./gradlew build` builds + tests the SPA; `./gradlew test`
// runs its Vitest suite (so the panel is part of the project gate).

plugins {
    base // clean / assemble / check / build lifecycle tasks
}

// Resolve npm even when Gradle's PATH lacks the Node bin dir (Homebrew / nvm).
fun findExecutable(name: String): String {
    val candidates = listOf(
        "/opt/homebrew/bin/$name",
        "/usr/local/bin/$name",
        "/usr/bin/$name",
        System.getenv("HOME") + "/.nvm/current/bin/$name",
    )
    return candidates.firstOrNull { file(it).exists() } ?: name
}

val npmCmd = findExecutable("npm")

// Reproducible install from the lockfile.
val npmInstall by tasks.registering(Exec::class) {
    description = "Install admin-panel dependencies (npm ci)"
    group = "build"
    workingDir = projectDir
    commandLine = listOf(npmCmd, "ci")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
}

val buildSpa by tasks.registering(Exec::class) {
    description = "Type-check + bundle the SPA (npm run build)"
    group = "build"
    workingDir = projectDir
    dependsOn(npmInstall)
    commandLine = listOf(npmCmd, "run", "build")
    inputs.dir("src")
    inputs.file("package.json")
    inputs.file("tsconfig.json")
    inputs.file("vite.config.ts")
    inputs.file("index.html")
    outputs.dir("dist")
}

// Named `test` so it joins the project-wide `./gradlew test` aggregate (the gate).
val test by tasks.registering(Exec::class) {
    description = "Run the SPA Vitest suite"
    group = "verification"
    workingDir = projectDir
    dependsOn(npmInstall)
    commandLine = listOf(npmCmd, "test")
    inputs.dir("src")
    inputs.file("package.json")
    // No outputs → always runs with the rest of the gate; let a failure fail the build.
}

val dev by tasks.registering(Exec::class) {
    description = "Start the Vite dev server"
    group = "application"
    workingDir = projectDir
    dependsOn(npmInstall)
    commandLine = listOf(npmCmd, "run", "dev")
}

tasks.named("assemble") { dependsOn(buildSpa) }
tasks.named("check") { dependsOn(test) }
tasks.named("clean") {
    doLast {
        delete("dist")
        delete("coverage")
    }
}
