package com.symptree.core.llm

import com.symptree.core.symptomizer.Option
import com.symptree.core.symptomizer.QuestionNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * AC-PWVTT4.4 + AC-PWVTT4.6 + AC-PWVTT4.7 — `LlmRouterClient` port shape,
 * `Intent` enum, and `RouterValidator` happy + reject branches.
 *
 * The `LlmRouterClient` port and DTOs are exercised here via type references
 * (constructor calls + enum mentions); RouterValidator covers the rejection
 * branches with a structured reason on `LlmRejectedException`.
 */
class RouterValidatorTest {

    // ---------- AC-PWVTT4.4: port + DTO shapes ----------

    @Test
    fun `AC-4 Intent enum has the four spec values in spec order`() {
        val values = Intent.entries.map { it.name }
        assertEquals(listOf("SYMPTOM", "BOOKING", "FAQ", "FALLBACK"), values)
    }

    @Test
    fun `AC-4 IntentClassification DTO carries intent confidence and optional reason`() {
        val c = IntentClassification(intent = Intent.SYMPTOM, confidence = 0.91, reason = "hello")
        assertEquals(Intent.SYMPTOM, c.intent)
        assertEquals(0.91, c.confidence, 0.0)
        assertEquals("hello", c.reason)
    }

    @Test
    fun `AC-4 OptionMapping DTO carries pickedOptionValue confidence and optional clarifyingQuestion`() {
        val m = OptionMapping(pickedOptionValue = "yes", confidence = 0.8, clarifyingQuestion = null)
        assertEquals("yes", m.pickedOptionValue)
        assertEquals(0.8, m.confidence, 0.0)
        assertEquals(null, m.clarifyingQuestion)
    }

    // ---------- AC-PWVTT4.6: validateIntent ----------

    @Test
    fun `AC-6 validateIntent returns intent when in allowed set AND confidence at threshold`() {
        val c = IntentClassification(Intent.SYMPTOM, 0.6, null)
        val returned = RouterValidator.validateIntent(
            classification = c,
            allowed = setOf(Intent.SYMPTOM, Intent.BOOKING),
            confidenceThreshold = 0.6,
        )
        assertEquals(Intent.SYMPTOM, returned)
    }

    @Test
    fun `AC-6 validateIntent returns intent when above threshold and in allowed set`() {
        val c = IntentClassification(Intent.BOOKING, 0.95, null)
        val returned = RouterValidator.validateIntent(
            classification = c,
            allowed = setOf(Intent.SYMPTOM, Intent.BOOKING),
        )
        assertEquals(Intent.BOOKING, returned)
    }

    @Test
    fun `AC-6 validateIntent rejects when intent not in allowed set`() {
        val c = IntentClassification(Intent.FAQ, 0.99, null)
        try {
            RouterValidator.validateIntent(c, allowed = setOf(Intent.SYMPTOM, Intent.BOOKING))
            fail("expected LlmRejectedException")
        } catch (e: LlmRejectedException) {
            assertEquals(LlmRejectedException.Reason.OUT_OF_SET, e.reason)
        }
    }

    @Test
    fun `AC-6 validateIntent rejects when confidence below default threshold`() {
        val c = IntentClassification(Intent.SYMPTOM, 0.59, null)
        try {
            RouterValidator.validateIntent(c, allowed = setOf(Intent.SYMPTOM))
            fail("expected LlmRejectedException")
        } catch (e: LlmRejectedException) {
            assertEquals(LlmRejectedException.Reason.LOW_CONFIDENCE, e.reason)
        }
    }

    @Test
    fun `AC-6 validateIntent honors per-call confidenceThreshold override`() {
        val c = IntentClassification(Intent.SYMPTOM, 0.75, null)
        // Default threshold (0.6) would accept; raise to 0.8 to force rejection.
        try {
            RouterValidator.validateIntent(
                classification = c,
                allowed = setOf(Intent.SYMPTOM),
                confidenceThreshold = 0.8,
            )
            fail("expected LlmRejectedException for raised threshold")
        } catch (e: LlmRejectedException) {
            assertEquals(LlmRejectedException.Reason.LOW_CONFIDENCE, e.reason)
        }
    }

    // ---------- AC-PWVTT4.7: validateOption ----------

    @Test
    fun `AC-7 validateOption returns pickedOptionValue when present in node options AND confidence threshold met`() {
        val node = sampleQuestionNode()
        val mapping = OptionMapping(pickedOptionValue = "yes", confidence = 0.8, clarifyingQuestion = null)
        val picked = RouterValidator.validateOption(mapping, node)
        assertEquals("yes", picked)
    }

    @Test
    fun `AC-7 validateOption rejects with OUT_OF_SET when pickedOptionValue not in node options`() {
        val node = sampleQuestionNode()
        val mapping = OptionMapping(pickedOptionValue = "maybe", confidence = 0.99, clarifyingQuestion = null)
        try {
            RouterValidator.validateOption(mapping, node)
            fail("expected LlmRejectedException(OUT_OF_SET)")
        } catch (e: LlmRejectedException) {
            assertEquals(LlmRejectedException.Reason.OUT_OF_SET, e.reason)
        }
    }

    @Test
    fun `AC-7 validateOption rejects with LOW_CONFIDENCE when confidence below threshold`() {
        val node = sampleQuestionNode()
        val mapping = OptionMapping(pickedOptionValue = "yes", confidence = 0.5, clarifyingQuestion = null)
        try {
            RouterValidator.validateOption(mapping, node)
            fail("expected LlmRejectedException(LOW_CONFIDENCE)")
        } catch (e: LlmRejectedException) {
            assertEquals(LlmRejectedException.Reason.LOW_CONFIDENCE, e.reason)
        }
    }

    @Test
    fun `AC-7 validateOption rejects with NULL_PICK when pickedOptionValue is null`() {
        val node = sampleQuestionNode()
        val mapping = OptionMapping(
            pickedOptionValue = null,
            confidence = 0.95,
            clarifyingQuestion = "Did you mean yes or no?",
        )
        try {
            RouterValidator.validateOption(mapping, node)
            fail("expected LlmRejectedException(NULL_PICK)")
        } catch (e: LlmRejectedException) {
            assertEquals(LlmRejectedException.Reason.NULL_PICK, e.reason)
        }
    }

    @Test
    fun `AC-7 LlmRejectedException Reason enum carries exactly the documented values`() {
        val values = LlmRejectedException.Reason.entries.map { it.name }.toSet()
        val expected = setOf("OUT_OF_SET", "LOW_CONFIDENCE", "NULL_PICK", "NON_SYMPTOM_INTENT")
        assertEquals(
            expected,
            values,
            "expected reasons $expected; got $values — enum drift will silently change re-prompt UX",
        )
    }

    private fun sampleQuestionNode(): QuestionNode = QuestionNode(
        id = "q1",
        text = mapOf("ru" to "Внезапно?", "kk" to "Күтпеген?"),
        options = listOf(
            Option(value = "yes", label = mapOf("ru" to "Да", "kk" to "Иә"), next = "c1"),
            Option(value = "no", label = mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "q2"),
        ),
    )

    // ---------- AC-PWVTT4.8: validateAlgorithmSelection (LLM-driven routing) ----------

    @Test
    fun `AC-8 validateAlgorithmSelection returns null when LLM picked 'none' explicitly`() {
        val result = RouterValidator.validateAlgorithmSelection(
            selection = AlgorithmSelection(selectedId = null, confidence = 0.95, reason = "no match"),
            candidateIds = setOf(1L, 2L, 3L),
        )
        assertEquals(null, result, "explicit 'none' from LLM must surface as null regardless of confidence")
    }

    @Test
    fun `AC-8 validateAlgorithmSelection returns id when picked is in candidates AND confidence at threshold`() {
        val result = RouterValidator.validateAlgorithmSelection(
            selection = AlgorithmSelection(selectedId = 2L, confidence = 0.6, reason = null),
            candidateIds = setOf(1L, 2L, 3L),
        )
        assertEquals(2L, result)
    }

    @Test
    fun `AC-8 validateAlgorithmSelection rejects id not in candidates as null match`() {
        val result = RouterValidator.validateAlgorithmSelection(
            selection = AlgorithmSelection(selectedId = 99L, confidence = 0.95, reason = null),
            candidateIds = setOf(1L, 2L, 3L),
        )
        assertEquals(null, result, "model-version drift: id outside candidate set must NOT route the user")
    }

    @Test
    fun `AC-8 validateAlgorithmSelection rejects below-threshold confidence as null match`() {
        val result = RouterValidator.validateAlgorithmSelection(
            selection = AlgorithmSelection(selectedId = 1L, confidence = 0.5, reason = null),
            candidateIds = setOf(1L, 2L, 3L),
            confidenceThreshold = 0.6,
        )
        assertEquals(
            null, result,
            "below-threshold confidence must surface as no match — bot shows 'no flow' rather than gambling",
        )
    }
}
