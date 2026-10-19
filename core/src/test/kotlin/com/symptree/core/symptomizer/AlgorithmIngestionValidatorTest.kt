package com.symptree.core.symptomizer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-KT4697.3: `AlgorithmIngestionValidator.validate(algorithm, requiredLanguages)`
 * rejects trees with:
 *   (a) bad entry_point
 *   (b) dangling option.next
 *   (c) missing required language on any text-bearing field
 *
 * Errors are typed as `AlgorithmValidationException` carrying `nodeId` and
 * a `ValidationReason` (sealed: MissingEntryPoint, DanglingNext, MissingLanguage).
 */
class AlgorithmIngestionValidatorTest {

    private val ruKk = setOf("ru", "kk")

    // ---------- happy path ----------

    @Test
    fun `AC-3 happy path two-node tree with full RU+KK passes`() {
        val algo = twoNodeAlgorithm()
        // Should not throw.
        AlgorithmIngestionValidator.validate(algo, ruKk)
    }

    // ---------- (a) bad entry_point ----------

    @Test
    fun `AC-3 rejects when entry_point references missing node`() {
        val algo = twoNodeAlgorithm().copy(entryPoint = "no_such_node")
        val ex = assertThrowsValidation { AlgorithmIngestionValidator.validate(algo, ruKk) }
        assertEquals("no_such_node", ex.nodeId)
        val reason = ex.reason
        assertTrue(
            reason is ValidationReason.MissingEntryPoint,
            "expected MissingEntryPoint, got ${reason::class.simpleName}",
        )
    }

    // ---------- (b) dangling option.next ----------

    @Test
    fun `AC-3 rejects when an option next references a missing node`() {
        val algo = twoNodeAlgorithm().let { base ->
            val q = base.nodes["q1"] as QuestionNode
            val brokenOptions = q.options.toMutableList()
            brokenOptions[0] = brokenOptions[0].copy(next = "ghost_node")
            val brokenQ = q.copy(options = brokenOptions)
            base.copy(nodes = base.nodes.toMutableMap().apply { put("q1", brokenQ) })
        }
        val ex = assertThrowsValidation { AlgorithmIngestionValidator.validate(algo, ruKk) }
        assertEquals("q1", ex.nodeId, "error must name the node that owns the bad option")
        val reason = ex.reason
        assertTrue(
            reason is ValidationReason.DanglingNext,
            "expected DanglingNext, got ${reason::class.simpleName}",
        )
        assertEquals("ghost_node", reason.missingNext)
        assertEquals("yes", reason.optionValue)
    }

    // ---------- (c) missing language ----------

    @Test
    fun `AC-3 rejects when a QuestionNode text is missing a required language`() {
        val algo = twoNodeAlgorithm().let { base ->
            val q = base.nodes["q1"] as QuestionNode
            val brokenQ = q.copy(text = mapOf("ru" to q.text["ru"]!!)) // drop kk
            base.copy(nodes = base.nodes.toMutableMap().apply { put("q1", brokenQ) })
        }
        val ex = assertThrowsValidation { AlgorithmIngestionValidator.validate(algo, ruKk) }
        assertEquals("q1", ex.nodeId)
        val reason = ex.reason
        assertTrue(
            reason is ValidationReason.MissingLanguage,
            "expected MissingLanguage, got ${reason::class.simpleName}",
        )
        assertEquals("kk", reason.lang)
        assertEquals("text", reason.field)
    }

    @Test
    fun `AC-3 rejects when option label is missing a required language`() {
        val algo = twoNodeAlgorithm().let { base ->
            val q = base.nodes["q1"] as QuestionNode
            val opts = q.options.toMutableList()
            opts[0] = opts[0].copy(label = mapOf("ru" to "Да")) // drop kk
            val brokenQ = q.copy(options = opts)
            base.copy(nodes = base.nodes.toMutableMap().apply { put("q1", brokenQ) })
        }
        val ex = assertThrowsValidation { AlgorithmIngestionValidator.validate(algo, ruKk) }
        assertEquals("q1", ex.nodeId)
        val reason = ex.reason
        assertTrue(reason is ValidationReason.MissingLanguage)
        assertEquals("kk", reason.lang)
    }

    @Test
    fun `AC-3 rejects when conclusion diagnosis is missing a required language`() {
        val algo = twoNodeAlgorithm().let { base ->
            val c = base.nodes["demo_emergency"] as ConclusionNode
            val brokenC = c.copy(diagnosis = mapOf("ru" to c.diagnosis["ru"]!!)) // drop kk
            base.copy(nodes = base.nodes.toMutableMap().apply { put("demo_emergency", brokenC) })
        }
        val ex = assertThrowsValidation { AlgorithmIngestionValidator.validate(algo, ruKk) }
        assertEquals("demo_emergency", ex.nodeId)
        val reason = ex.reason
        assertTrue(reason is ValidationReason.MissingLanguage)
        assertEquals("kk", reason.lang)
        assertEquals("diagnosis", reason.field)
    }

    @Test
    fun `AC-3 rejects when conclusion action is missing a required language`() {
        val algo = twoNodeAlgorithm().let { base ->
            val c = base.nodes["demo_emergency"] as ConclusionNode
            val brokenC = c.copy(action = mapOf("ru" to c.action["ru"]!!)) // drop kk
            base.copy(nodes = base.nodes.toMutableMap().apply { put("demo_emergency", brokenC) })
        }
        val ex = assertThrowsValidation { AlgorithmIngestionValidator.validate(algo, ruKk) }
        assertEquals("demo_emergency", ex.nodeId)
        val reason = ex.reason
        assertTrue(reason is ValidationReason.MissingLanguage)
        assertEquals("kk", reason.lang)
        assertEquals("action", reason.field)
    }

    @Test
    fun `AC-3 rejects when metadata name is missing a required language`() {
        val algo = twoNodeAlgorithm().let { base ->
            base.copy(
                metadata = base.metadata.copy(
                    name = mapOf("ru" to base.metadata.name["ru"]!!),
                ),
            )
        }
        val ex = assertThrowsValidation { AlgorithmIngestionValidator.validate(algo, ruKk) }
        val reason = ex.reason
        assertTrue(reason is ValidationReason.MissingLanguage)
        assertEquals("kk", reason.lang)
        assertEquals("name", reason.field)
    }

    // ---------- helpers ----------

    private fun twoNodeAlgorithm(): Algorithm {
        return Algorithm(
            algorithmId = "symptom_demo_adult",
            version = "2.1.0",
            metadata = AlgorithmMetadata(
                name = mapOf("ru" to "ДЕМО-дерево", "kk" to "ДЕМО-ағаш"),
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
                        Option(
                            value = "yes",
                            label = mapOf("ru" to "Да", "kk" to "Иә"),
                            next = "demo_emergency",
                        ),
                        Option(
                            value = "no",
                            label = mapOf("ru" to "Нет", "kk" to "Жоқ"),
                            next = "demo_emergency",
                        ),
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

    private inline fun assertThrowsValidation(block: () -> Unit): AlgorithmValidationException {
        try {
            block()
            fail("expected AlgorithmValidationException")
        } catch (e: AlgorithmValidationException) {
            return e
        }
    }
}
