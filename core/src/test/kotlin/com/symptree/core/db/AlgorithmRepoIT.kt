package com.symptree.core.db

import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.symptomizer.Algorithm
import com.symptree.core.symptomizer.AlgorithmMetadata
import com.symptree.core.symptomizer.ClinicAction
import com.symptree.core.symptomizer.ConclusionNode
import com.symptree.core.symptomizer.Node
import com.symptree.core.symptomizer.Option
import com.symptree.core.symptomizer.QuestionNode
import com.symptree.core.symptomizer.Urgency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Direct integration tests for [AlgorithmRepo] against Testcontainers Postgres.
 *
 * AlgorithmSeedLoaderIT covers insert+findByKeyAndVersion via the seed loop;
 * this file exercises the same two public functions directly with hand-built
 * Algorithm fixtures so future regressions point at the repo, not the loader.
 */
class AlgorithmRepoIT {

    private lateinit var repo: AlgorithmRepo

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        repo = AlgorithmRepo(PostgresTestSupport.database())
    }

    @Test
    fun `insert returns the new row id and persists tree+metadata as JSONB`() {
        val algo = fixtureAlgorithm("symptom_x", "1.0.0")
        val id = repo.insert(algo, ingestedBy = "test")
        assertTrue(id > 0)

        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT algorithm_key, version, metadata::text, tree::text FROM algorithms WHERE id=?",
            ).use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "inserted row must be readable")
                    assertEquals("symptom_x", rs.getString(1))
                    assertEquals("1.0.0", rs.getString(2))
                    val meta = Json.parseToJsonElement(rs.getString(3)).jsonObject
                    val tree = Json.parseToJsonElement(rs.getString(4)).jsonObject
                    assertEquals("neurology", meta["category"]!!.jsonPrimitive.content)
                    assertEquals("q1", tree["entry_point"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun `findByKeyAndVersion returns the id of a present row`() {
        val algo = fixtureAlgorithm("symptom_y", "2.0.0")
        val id = repo.insert(algo, ingestedBy = "test")
        assertEquals(id, repo.findByKeyAndVersion("symptom_y", "2.0.0"))
    }

    @Test
    fun `findByKeyAndVersion returns null when key+version missing`() {
        assertNull(repo.findByKeyAndVersion("symptom_missing", "9.9.9"))
    }

    @Test
    fun `findByKeyAndVersion discriminates on version`() {
        val v1 = repo.insert(fixtureAlgorithm("symptom_z", "1.0.0"), ingestedBy = "test")
        val v2 = repo.insert(fixtureAlgorithm("symptom_z", "2.0.0"), ingestedBy = "test")
        assertNotEquals(v1, v2)
        assertEquals(v1, repo.findByKeyAndVersion("symptom_z", "1.0.0"))
        assertEquals(v2, repo.findByKeyAndVersion("symptom_z", "2.0.0"))
        assertNull(repo.findByKeyAndVersion("symptom_z", "3.0.0"))
    }

    @Test
    fun `inserting same key+version twice fails on the UNIQUE constraint`() {
        val algo = fixtureAlgorithm("symptom_dup", "1.0.0")
        repo.insert(algo, ingestedBy = "test")
        try {
            repo.insert(algo, ingestedBy = "test")
            fail("expected unique-violation on second insert")
        } catch (e: Exception) {
            val msg = (e.message ?: "") + (e.cause?.message ?: "")
            assertTrue(
                msg.contains("unique", ignoreCase = true) || msg.contains("duplicate", ignoreCase = true),
                "expected unique-violation, got: $msg",
            )
        }
        assertNotNull(repo.findByKeyAndVersion("symptom_dup", "1.0.0"))
    }

    private fun fixtureAlgorithm(key: String, version: String): Algorithm {
        val conclusion: Node = ConclusionNode(
            id = "c1",
            urgency = Urgency.ROUTINE,
            diagnosis = mapOf("ru" to "Тест", "kk" to "Тест"),
            action = mapOf("ru" to "Терапевт", "kk" to "Терапевт"),
            specialist = "therapist",
            clinicAction = ClinicAction.BOOK_APPOINTMENT,
            followUpDays = 7,
        )
        val question: Node = QuestionNode(
            id = "q1",
            text = mapOf("ru" to "Вопрос?", "kk" to "Сұрақ?"),
            options = listOf(
                Option(value = "yes", label = mapOf("ru" to "Да", "kk" to "Иә"), next = "c1"),
            ),
        )
        return Algorithm(
            algorithmId = key,
            version = version,
            metadata = AlgorithmMetadata(
                name = mapOf("ru" to "Имя", "kk" to "Аты"),
                category = "neurology",
                ageGroup = "adult",
                estimatedTimeMinutes = 1,
            ),
            entryPoint = "q1",
            nodes = mapOf("q1" to question, "c1" to conclusion),
        )
    }
}
