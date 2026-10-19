package com.symptree.core.symptomizer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-KT4697.2: An `Algorithm` Kotlin model deserializes from the JSON schema
 * in technical-spec.md § 2.2. Every text-bearing field is `Map<String, String>`
 * keyed by ISO language code.
 *
 * The fixture below is the two-node worked example (`q1` + `demo_emergency`)
 * from technical-spec.md § 2.2. Its content is synthetic placeholder text —
 * this test exercises the wire format, not any clinical rule.
 */
class AlgorithmDeserializationTest {

    private val json = AlgorithmJson.instance

    private val workedExampleJson = """
    {
      "algorithm_id": "symptom_demo_adult",
      "version": "2.1.0",
      "metadata": {
        "name": {"ru": "ДЕМО: пример дерева решений", "kk": "ДЕМО: шешім ағашының мысалы"},
        "category": "demo",
        "age_group": "adult",
        "estimated_time_minutes": 3
      },
      "entry_point": "q1",
      "nodes": {
        "q1": {
          "type": "question",
          "text": {"ru": "ДЕМО, вопрос 1: признак A присутствует?", "kk": "ДЕМО, 1-сұрақ: A белгісі бар ма?"},
          "options": [
            {"value": "yes", "label": {"ru": "Да", "kk": "Иә"}, "next": "demo_emergency"},
            {"value": "no",  "label": {"ru": "Нет", "kk": "Жоқ"}, "next": "q2"}
          ]
        },
        "demo_emergency": {
          "type": "conclusion",
          "urgency": "emergency",
          "diagnosis": {"ru": "ДЕМО-исход: пример экстренного исхода", "kk": "ДЕМО-қорытынды: шұғыл қорытынды мысалы"},
          "action": {"ru": "ДЕМО: пример экстренного действия", "kk": "ДЕМО: шұғыл әрекет мысалы"},
          "specialist": null,
          "tests": [],
          "additional_tests": [],
          "preparation": {},
          "clinic_action": "bypass_appointment",
          "follow_up_days": null
        }
      }
    }
    """.trimIndent()

    @Test
    fun `AC-2 worked example deserializes into Algorithm with i18n maps everywhere`() {
        val algorithm = json.decodeFromString(Algorithm.serializer(), workedExampleJson)

        // Identity + version
        assertEquals("symptom_demo_adult", algorithm.algorithmId)
        assertEquals("2.1.0", algorithm.version)

        // Metadata
        assertEquals("ДЕМО: пример дерева решений", algorithm.metadata.name["ru"])
        assertEquals("ДЕМО: шешім ағашының мысалы", algorithm.metadata.name["kk"])
        assertEquals("demo", algorithm.metadata.category)
        assertEquals("adult", algorithm.metadata.ageGroup)
        assertEquals(3, algorithm.metadata.estimatedTimeMinutes)

        // Entry point
        assertEquals("q1", algorithm.entryPoint)

        // q1 — QuestionNode
        val q1 = algorithm.nodes["q1"] ?: fail("q1 missing")
        assertTrue(q1 is QuestionNode, "q1 must be QuestionNode, was ${q1::class.simpleName}")
        assertEquals("q1", q1.id)
        assertEquals("ДЕМО, вопрос 1: признак A присутствует?", q1.text["ru"])
        assertEquals("ДЕМО, 1-сұрақ: A белгісі бар ма?", q1.text["kk"])
        assertEquals(2, q1.options.size)

        val yes = q1.options[0]
        assertEquals("yes", yes.value)
        assertEquals("Да", yes.label["ru"])
        assertEquals("Иә", yes.label["kk"])
        assertEquals("demo_emergency", yes.next)

        val no = q1.options[1]
        assertEquals("no", no.value)
        assertEquals("Нет", no.label["ru"])
        assertEquals("Жоқ", no.label["kk"])
        assertEquals("q2", no.next)

        // demo_emergency — ConclusionNode
        val emergency = algorithm.nodes["demo_emergency"] ?: fail("demo_emergency missing")
        assertTrue(emergency is ConclusionNode, "demo_emergency must be ConclusionNode")
        assertEquals("demo_emergency", emergency.id)
        assertEquals(Urgency.EMERGENCY, emergency.urgency)
        assertEquals("ДЕМО-исход: пример экстренного исхода", emergency.diagnosis["ru"])
        assertEquals("ДЕМО-қорытынды: шұғыл қорытынды мысалы", emergency.diagnosis["kk"])
        assertEquals("ДЕМО: пример экстренного действия", emergency.action["ru"])
        assertEquals("ДЕМО: шұғыл әрекет мысалы", emergency.action["kk"])
        assertNull(emergency.specialist)
        assertEquals(emptyList<String>(), emergency.tests)
        assertEquals(emptyList<String>(), emergency.additionalTests)
        assertTrue(emergency.preparation.isEmpty(), "preparation must be empty")
        assertEquals(ClinicAction.BYPASS_APPOINTMENT, emergency.clinicAction)
        assertNull(emergency.followUpDays)
    }

    @Test
    fun `AC-2 i18n text field is a Map keyed by language code`() {
        val algorithm = json.decodeFromString(Algorithm.serializer(), workedExampleJson)
        val q1 = algorithm.nodes["q1"] as QuestionNode
        // Compile-time + runtime: text is a Map<String, String>.
        val text: Map<String, String> = q1.text
        assertEquals(setOf("ru", "kk"), text.keys)
        assertNotNull(text["ru"])
        assertNotNull(text["kk"])
    }
}
