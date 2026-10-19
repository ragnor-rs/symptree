package com.symptree.core.symptomizer

import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.integration.PostgresTestSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * AC-KT4697.6 (DB layer):
 *   AlgorithmSeedLoader.seed(repo, resourcePath = "algorithms") reads each
 *   classpath JSON file under `resourcePath`, validates it against RU+KK,
 *   and inserts it via repo.insert(algo, ingestedBy = "seed") IF
 *   (algorithm_key, version) is not already present. Idempotent on re-run.
 *
 * Integration test: seed twice against fresh Postgres → exactly one row
 * (the repository ships the synthetic demo tree only).
 */
class AlgorithmSeedLoaderIT {

    private lateinit var repo: AlgorithmRepo

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        repo = AlgorithmRepo(PostgresTestSupport.database())
    }

    @Test
    fun `AC-6 seeding twice against fresh DB is idempotent`() {
        AlgorithmSeedLoader.seed(repo)
        AlgorithmSeedLoader.seed(repo)

        val count = countAlgorithmRows()
        assertEquals(
            AlgorithmSeedLoader.KNOWN_ALGORITHMS.size,
            count,
            "expected one row per shipped algorithm after idempotent reseed",
        )
    }

    @Test
    fun `AC-6 seeding once writes the shipped demo algorithm`() {
        AlgorithmSeedLoader.seed(repo)

        val keys = mutableSetOf<String>()
        val versions = mutableSetOf<String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT algorithm_key, version FROM algorithms").executeQuery().use { rs ->
                while (rs.next()) {
                    keys.add(rs.getString(1))
                    versions.add(rs.getString(2))
                }
            }
        }
        assertEquals(setOf("symptom_demo_adult"), keys)
        assertEquals(setOf("1.0.0"), versions, "expected the demo algorithm at version 1.0.0")
    }

    private fun countAlgorithmRows(): Int {
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM algorithms").executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}
