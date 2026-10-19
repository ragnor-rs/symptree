package com.symptree.core.symptomizer

import java.io.File

/**
 * Shared test helpers for symptomizer fixture tests.
 *
 * Both [AlgorithmResourcesTest] and [AlgorithmExhaustiveWalkTest] need to
 * locate the on-disk algorithm fixture directory; both also need to parse
 * those fixtures. Centralising both bits keeps the test bodies small and
 * avoids drift between the two file-finding strategies.
 */
internal object AlgorithmTestFixtures {

    /**
     * Locate the `algorithms/` resource directory shipped under `core/`.
     *
     * Resolution is best-effort: it tries the module-relative path first
     * (Gradle's default test working dir), then a couple of repo-rooted
     * fallbacks so the tests also run from the repo root.
     */
    fun resolveResourceDir(): File {
        val candidates = listOf(
            File("src/main/resources/algorithms"),
            File("core/src/main/resources/algorithms"),
            File("../core/src/main/resources/algorithms"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Could not locate algorithms resource dir; tried: ${candidates.map { it.absolutePath }}",
            )
    }

    /** List every `*.json` file under [resolveResourceDir]. */
    fun listFixtureFiles(): List<File> =
        resolveResourceDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.toList()
            .orEmpty()
}
