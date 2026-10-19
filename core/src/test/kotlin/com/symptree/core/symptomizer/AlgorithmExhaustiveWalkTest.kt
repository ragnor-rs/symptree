package com.symptree.core.symptomizer

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-KT4697.7: data-driven exhaustive walk.
 *
 * For each algorithm under `core/src/main/resources/algorithms/`:
 *   walk depth-first from entry_point, exercising every option at every
 *   QuestionNode reached. For each terminal path:
 *     (a) urgency ∈ {EMERGENCY, URGENT, ROUTINE, SELF_CARE}
 *     (b) clinic_action ∈ {BOOK_APPOINTMENT, BYPASS_APPOINTMENT,
 *                          RECOMMEND_PHARMACY, RECOMMEND_SELF_CARE}
 *     (c) specialist is null or a non-blank string
 *     (d) diagnosis + action have non-blank "ru" and "kk" entries
 *
 * Depth is capped at 100 to catch accidental cycles.
 */
class AlgorithmExhaustiveWalkTest {

    private val json = AlgorithmJson.instance
    private val maxDepth = 100

    @Test
    fun `AC-7 every shipped algorithm walks exhaustively with well-formed conclusions`() {
        val files = AlgorithmTestFixtures.listFixtureFiles()
        assertTrue(
            files.isNotEmpty(),
            "expected at least one algorithm fixture under " +
                AlgorithmTestFixtures.resolveResourceDir(),
        )

        for (file in files) {
            val algo = json.decodeFromString(Algorithm.serializer(), file.readText())
            walkAlgorithm(file.name, algo)
        }
    }

    private fun walkAlgorithm(fileName: String, algo: Algorithm) {
        val entry = algo.nodes[algo.entryPoint]
            ?: fail("[$fileName] entry_point '${algo.entryPoint}' missing from nodes map")
        when (entry) {
            is ConclusionNode -> assertConclusionWellFormed(fileName, entry)
            is QuestionNode -> walkFrom(fileName, algo, entry, depth = 0)
        }
    }

    private fun walkFrom(fileName: String, algo: Algorithm, node: QuestionNode, depth: Int) {
        assertTrue(
            depth <= maxDepth,
            "[$fileName] walk exceeded max depth $maxDepth at node ${node.id} — accidental cycle?",
        )
        for (option in node.options) {
            val next = algo.nodes[option.next]
                ?: fail("[$fileName] node ${node.id} option ${option.value} → missing ${option.next}")
            when (next) {
                is ConclusionNode -> assertConclusionWellFormed(fileName, next)
                is QuestionNode -> walkFrom(fileName, algo, next, depth + 1)
            }
        }
    }

    private fun assertConclusionWellFormed(fileName: String, c: ConclusionNode) {
        // (a) urgency enum (sealed by the type system, but assert defensively)
        assertTrue(
            c.urgency in setOf(Urgency.EMERGENCY, Urgency.URGENT, Urgency.ROUTINE, Urgency.SELF_CARE),
            "[$fileName/${c.id}] urgency=${c.urgency} not in expected set",
        )
        // (b) clinic_action enum
        assertTrue(
            c.clinicAction in setOf(
                ClinicAction.BOOK_APPOINTMENT,
                ClinicAction.BYPASS_APPOINTMENT,
                ClinicAction.RECOMMEND_PHARMACY,
                ClinicAction.RECOMMEND_SELF_CARE,
            ),
            "[$fileName/${c.id}] clinic_action=${c.clinicAction} not in expected set",
        )
        // (c) specialist is null or non-blank
        val sp = c.specialist
        if (sp != null) {
            assertTrue(
                sp.isNotBlank(),
                "[$fileName/${c.id}] specialist is non-null but blank",
            )
        }
        // (d) diagnosis + action have non-blank ru + kk
        for (lang in listOf("ru", "kk")) {
            val dg = c.diagnosis[lang]
            assertTrue(
                dg != null && dg.isNotBlank(),
                "[$fileName/${c.id}] diagnosis[$lang] missing or blank",
            )
            val ac = c.action[lang]
            assertTrue(
                ac != null && ac.isNotBlank(),
                "[$fileName/${c.id}] action[$lang] missing or blank",
            )
        }
    }

}
