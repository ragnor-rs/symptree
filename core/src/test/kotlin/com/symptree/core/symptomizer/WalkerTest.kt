package com.symptree.core.symptomizer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-KT4697.4 + AC-KT4697.5: pure Walker — no I/O, no Clock, no state.
 *   walk(algorithm, currentNodeId, pickedOptionValue) -> WalkResult
 *   entry(algorithm) -> QuestionNode (throws if entry is conclusion)
 *   availableOptionValues(algorithm, currentNodeId) -> Set<String>
 *
 * Errors:
 *   IllegalAlgorithmStateException — unknown currentNodeId, or currentNodeId
 *     resolves to a ConclusionNode (cannot walk from terminal).
 *   InvalidOptionException — pickedOptionValue not in the current node's set.
 */
class WalkerTest {

    // ---------- AC-4: happy path ----------

    @Test
    fun `AC-4 walk follows option next to a QuestionNode and returns NextQuestion`() {
        val algo = threeNodeAlgorithm()
        val result = Walker.walk(algo, currentNodeId = "q1", pickedOptionValue = "no")
        assertTrue(
            result is WalkResult.NextQuestion,
            "expected NextQuestion, got ${result::class.simpleName}",
        )
        assertEquals("q2", result.node.id)
    }

    @Test
    fun `AC-4 walk follows option next to a ConclusionNode and returns Conclusion`() {
        val algo = threeNodeAlgorithm()
        val result = Walker.walk(algo, currentNodeId = "q1", pickedOptionValue = "yes")
        assertTrue(
            result is WalkResult.Conclusion,
            "expected Conclusion, got ${result::class.simpleName}",
        )
        assertEquals("demo_emergency", result.node.id)
        assertEquals(Urgency.EMERGENCY, result.node.urgency)
    }

    @Test
    fun `AC-4 entry returns the entry-point QuestionNode`() {
        val algo = threeNodeAlgorithm()
        val entry = Walker.entry(algo)
        assertEquals("q1", entry.id)
    }

    @Test
    fun `AC-4 entry throws when entry_point resolves to a ConclusionNode`() {
        val algo = threeNodeAlgorithm().copy(entryPoint = "demo_emergency")
        try {
            Walker.entry(algo)
            fail("expected IllegalAlgorithmStateException")
        } catch (e: IllegalAlgorithmStateException) {
            assertEquals("demo_emergency", e.nodeId)
        }
    }

    @Test
    fun `AC-4 availableOptionValues returns the option-value set of the current QuestionNode`() {
        val algo = threeNodeAlgorithm()
        assertEquals(setOf("yes", "no"), Walker.availableOptionValues(algo, "q1"))
    }

    // ---------- AC-5: error branches ----------

    @Test
    fun `AC-5 walk throws IllegalAlgorithmStateException when currentNodeId is unknown`() {
        val algo = threeNodeAlgorithm()
        try {
            Walker.walk(algo, currentNodeId = "ghost", pickedOptionValue = "yes")
            fail("expected IllegalAlgorithmStateException")
        } catch (e: IllegalAlgorithmStateException) {
            assertEquals("ghost", e.nodeId)
        }
    }

    @Test
    fun `AC-5 walk throws InvalidOptionException when picked option not in current node's option set`() {
        val algo = threeNodeAlgorithm()
        try {
            Walker.walk(algo, currentNodeId = "q1", pickedOptionValue = "maybe")
            fail("expected InvalidOptionException")
        } catch (e: InvalidOptionException) {
            assertEquals("q1", e.nodeId)
            assertEquals("maybe", e.optionValue)
            assertEquals(setOf("yes", "no"), e.availableOptionValues)
        }
    }

    @Test
    fun `AC-5 walk throws IllegalAlgorithmStateException when currentNodeId is a ConclusionNode`() {
        val algo = threeNodeAlgorithm()
        try {
            Walker.walk(algo, currentNodeId = "demo_emergency", pickedOptionValue = "yes")
            fail("expected IllegalAlgorithmStateException")
        } catch (e: IllegalAlgorithmStateException) {
            assertEquals("demo_emergency", e.nodeId)
        }
    }

    @Test
    fun `AC-5 availableOptionValues throws IllegalAlgorithmStateException on unknown node`() {
        val algo = threeNodeAlgorithm()
        try {
            Walker.availableOptionValues(algo, "ghost")
            fail("expected IllegalAlgorithmStateException")
        } catch (e: IllegalAlgorithmStateException) {
            assertEquals("ghost", e.nodeId)
        }
    }

    @Test
    fun `AC-5 availableOptionValues throws IllegalAlgorithmStateException on conclusion node`() {
        val algo = threeNodeAlgorithm()
        try {
            Walker.availableOptionValues(algo, "demo_emergency")
            fail("expected IllegalAlgorithmStateException")
        } catch (e: IllegalAlgorithmStateException) {
            assertEquals("demo_emergency", e.nodeId)
        }
    }

    // ---------- helpers ----------

    private fun threeNodeAlgorithm(): Algorithm = Algorithm(
        algorithmId = "symptom_demo_adult",
        version = "2.1.0",
        metadata = AlgorithmMetadata(
            name = mapOf("ru" to "Г", "kk" to "Б"),
            category = "neurology",
            ageGroup = "adult",
            estimatedTimeMinutes = 3,
        ),
        entryPoint = "q1",
        nodes = mapOf(
            "q1" to QuestionNode(
                id = "q1",
                text = mapOf("ru" to "Внезапно?", "kk" to "Күтпеген?"),
                options = listOf(
                    Option("yes", mapOf("ru" to "Да", "kk" to "Иә"), next = "demo_emergency"),
                    Option("no", mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "q2"),
                ),
            ),
            "q2" to QuestionNode(
                id = "q2",
                text = mapOf("ru" to "Долго?", "kk" to "Ұзақ?"),
                options = listOf(
                    Option("yes", mapOf("ru" to "Да", "kk" to "Иә"), next = "demo_emergency"),
                    Option("no", mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "demo_emergency"),
                ),
            ),
            "demo_emergency" to ConclusionNode(
                id = "demo_emergency",
                urgency = Urgency.EMERGENCY,
                diagnosis = mapOf("ru" to "ДЕМО-исход", "kk" to "ДЕМО-қорытынды"),
                action = mapOf("ru" to "ДЕМО-действие", "kk" to "ДЕМО-әрекет"),
                specialist = null,
                tests = emptyList(),
                additionalTests = emptyList(),
                preparation = emptyMap(),
                clinicAction = ClinicAction.BYPASS_APPOINTMENT,
                followUpDays = null,
            ),
        ),
    )
}
