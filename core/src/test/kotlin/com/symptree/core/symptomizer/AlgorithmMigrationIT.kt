package com.symptree.core.symptomizer

import com.symptree.core.integration.PostgresTestSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-KT4697.1: `algorithms` + `clinic_algorithms` tables exist matching
 * technical-spec.md § 2.1; `02-migrate-algorithms.sql` applies cleanly
 * on top of the minimal shell from `01-migrate-clinics.sql`.
 *
 * After 02 runs, `algorithms` must have these columns (in addition to the
 * shell columns from 01):
 *   - version          TEXT NOT NULL
 *   - metadata         JSONB NOT NULL
 *   - tree             JSONB NOT NULL
 *
 * And the `UNIQUE(algorithm_key)` constraint from 01 must be dropped in
 * favour of `UNIQUE(algorithm_key, version)`.
 */
class AlgorithmMigrationIT {

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
    }

    @Test
    fun `AC-1 algorithms table has version metadata tree columns after 02 migration`() {
        val expected = mapOf(
            "id" to "bigint",
            "algorithm_key" to "text",
            "version" to "text",
            "metadata" to "jsonb",
            "tree" to "jsonb",
            "is_active" to "boolean",
            "created_at" to "timestamp with time zone",
        )
        val seen = mutableMapOf<String, String>()
        val notNullCols = mutableSetOf<String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = 'algorithms'",
            ).executeQuery().use { rs ->
                while (rs.next()) {
                    val col = rs.getString(1)
                    seen[col] = rs.getString(2)
                    if (rs.getString(3).equals("NO", ignoreCase = true)) notNullCols.add(col)
                }
            }
        }
        for ((col, type) in expected) {
            val actual = seen[col] ?: fail("algorithms table is missing column $col; saw: $seen")
            assertEquals(type, actual, "column $col has unexpected type")
        }
        assertTrue("version" in notNullCols, "version must be NOT NULL")
        assertTrue("metadata" in notNullCols, "metadata must be NOT NULL")
        assertTrue("tree" in notNullCols, "tree must be NOT NULL")
    }

    @Test
    fun `AC-1 algorithms enforces UNIQUE on algorithm_key+version not algorithm_key alone`() {
        // Insert two rows with same key, different version — must succeed.
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO algorithms (algorithm_key, version, metadata, tree, is_active) " +
                    "VALUES (?, ?, ?::jsonb, ?::jsonb, TRUE)",
            ).use { ps ->
                ps.setString(1, "symptom_demo_adult")
                ps.setString(2, "2.0.0")
                ps.setString(3, "{}")
                ps.setString(4, "{}")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO algorithms (algorithm_key, version, metadata, tree, is_active) " +
                    "VALUES (?, ?, ?::jsonb, ?::jsonb, TRUE)",
            ).use { ps ->
                ps.setString(1, "symptom_demo_adult")
                ps.setString(2, "2.1.0")
                ps.setString(3, "{}")
                ps.setString(4, "{}")
                ps.executeUpdate()
            }
        }

        // Insert two rows with the same (key, version) — must FAIL.
        try {
            PostgresTestSupport.database().getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO algorithms (algorithm_key, version, metadata, tree, is_active) " +
                        "VALUES (?, ?, ?::jsonb, ?::jsonb, TRUE)",
                ).use { ps ->
                    ps.setString(1, "symptom_demo_adult")
                    ps.setString(2, "2.0.0")
                    ps.setString(3, "{}")
                    ps.setString(4, "{}")
                    ps.executeUpdate()
                }
            }
            fail("expected (algorithm_key, version) uniqueness violation")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("unique", ignoreCase = true) == true ||
                    e.message?.contains("duplicate", ignoreCase = true) == true ||
                    e.cause?.message?.contains("unique", ignoreCase = true) == true,
                "expected unique-violation error, got: ${e.message}",
            )
        }
    }

    @Test
    fun `AC-1 clinic_algorithms table still exists after 02 migration`() {
        val cols = mutableSetOf<String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema='public' AND table_name='clinic_algorithms'",
            ).executeQuery().use { rs ->
                while (rs.next()) cols.add(rs.getString(1))
            }
        }
        assertTrue(
            cols.containsAll(listOf("clinic_id", "algorithm_id", "enabled")),
            "clinic_algorithms missing required columns; got: $cols",
        )
    }
}
