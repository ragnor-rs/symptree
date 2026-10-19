package com.symptree.core.symptomizer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Resource layer (no DB): assert the shipped algorithm fixtures are well-formed.
 *
 * The repository ships exactly one **synthetic demo tree**. It carries no
 * clinical meaning — its text is placeholder content — but it is the reference
 * example for the schema in technical-spec.md § 2.2, so it must parse, validate
 * against RU+KK, and exercise every [Urgency] and [ClinicAction] value.
 */
class AlgorithmResourcesTest {

    private val json = AlgorithmJson.instance

    @Test
    fun `the algorithms resource directory ships at least one tree`() {
        val dir = AlgorithmTestFixtures.resolveResourceDir()
        assertTrue(dir.isDirectory, "expected directory: ${dir.absolutePath}")
        val files = AlgorithmTestFixtures.listFixtureFiles()
        assertTrue(files.isNotEmpty(), "expected >=1 algorithm JSON file, got none in ${dir.absolutePath}")
    }

    @Test
    fun `the shipped catalog is the synthetic demo tree only`() {
        val parsed = AlgorithmTestFixtures.listFixtureFiles()
            .map { json.decodeFromString(Algorithm.serializer(), it.readText()) }
        assertEquals(
            setOf("symptom_demo_adult"),
            parsed.map { it.algorithmId }.toSet(),
            "the public repository ships the demo tree only; clinical trees are operator-supplied",
        )
    }

    @Test
    fun `every shipped algorithm declares a version`() {
        for (file in AlgorithmTestFixtures.listFixtureFiles()) {
            val algo = json.decodeFromString(Algorithm.serializer(), file.readText())
            assertTrue(algo.version.isNotBlank(), "blank version in ${file.name}")
        }
    }

    @Test
    fun `every shipped algorithm validates against RU+KK`() {
        for (file in AlgorithmTestFixtures.listFixtureFiles()) {
            val algo = json.decodeFromString(Algorithm.serializer(), file.readText())
            // Should not throw.
            AlgorithmIngestionValidator.validate(algo, setOf("ru", "kk"))
        }
    }

    @Test
    fun `no shipped algorithm contains the needs-translation placeholder marker`() {
        // Per testing-spec: "no `[needs translation]` markers in any shipped algorithm".
        // The validator's per-language presence check would not catch a literal
        // marker string slipping into a value — this is the explicit sweep.
        val marker = "[needs translation]"
        for (file in AlgorithmTestFixtures.listFixtureFiles()) {
            val text = file.readText()
            assertTrue(
                marker !in text,
                "shipped algorithm ${file.name} contains '$marker' — replace with a real translation",
            )
        }
    }

    @Test
    fun `the demo tree exercises every urgency and clinic-action value`() {
        // The demo tree doubles as the schema reference, so it must cover the
        // whole enum surface — otherwise a value could be added to the model
        // with no shipped example of how it serializes.
        val conclusions = AlgorithmTestFixtures.listFixtureFiles()
            .map { json.decodeFromString(Algorithm.serializer(), it.readText()) }
            .flatMap { it.nodes.values }
            .filterIsInstance<ConclusionNode>()

        assertEquals(
            Urgency.entries.toSet(),
            conclusions.map { it.urgency }.toSet(),
            "demo tree must show every Urgency value",
        )
        assertEquals(
            ClinicAction.entries.toSet(),
            conclusions.map { it.clinicAction }.toSet(),
            "demo tree must show every ClinicAction value",
        )
        assertTrue(
            conclusions.any { it.tests.isNotEmpty() && it.preparation.isNotEmpty() },
            "demo tree must include a conclusion carrying tests + preparation",
        )
    }
}
