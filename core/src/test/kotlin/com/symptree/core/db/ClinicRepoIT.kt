package com.symptree.core.db

import com.symptree.core.Fixtures
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.CreateClinicRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ClinicRepoIT {

    private lateinit var repo: ClinicRepo

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        repo = ClinicRepo(PostgresTestSupport.database())
    }

    // ---------------- AC-M3WYNS.1 — schema ----------------

    @Test
    fun `AC-1 clinics table has every required column with the expected type`() {
        val expected = mapOf(
            "id" to "bigint",
            "slug" to "text",
            "name" to "text",
            "default_language" to "text",
            "allowed_languages" to "ARRAY",
            "config" to "jsonb",
            "is_active" to "boolean",
            "created_at" to "timestamp with time zone",
            "updated_at" to "timestamp with time zone",
        )
        val seen = mutableMapOf<String, String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = 'clinics'",
            ).executeQuery().use { rs ->
                while (rs.next()) seen[rs.getString(1)] = rs.getString(2)
            }
        }
        for ((col, type) in expected) {
            val actual = seen[col] ?: fail("clinics table is missing column $col; saw: $seen")
            assertEquals(type, actual, "column $col has unexpected type")
        }
    }

    @Test
    fun `AC-1 slug column has a unique constraint`() {
        val first = CreateClinicRequest("dup-slug", "A", "ru", listOf("ru"), JsonObject(emptyMap()))
        repo.insertClinic(first)
        val collision = CreateClinicRequest("dup-slug", "B", "ru", listOf("ru"), JsonObject(emptyMap()))
        try {
            repo.insertClinic(collision)
            fail("expected slug uniqueness violation")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("dup", ignoreCase = true) == true ||
                    e.message?.contains("unique", ignoreCase = true) == true ||
                    e.cause?.message?.contains("unique", ignoreCase = true) == true,
                "expected unique-violation error, got: ${e.message}",
            )
        }
    }

    @Test
    fun `AC-1 clinic_algorithms table exists with clinic_id algorithm_id enabled`() {
        val cols = mutableSetOf<String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema='public' AND table_name='clinic_algorithms'",
            ).executeQuery().use { rs ->
                while (rs.next()) cols.add(rs.getString(1))
            }
        }
        assertTrue(cols.containsAll(listOf("clinic_id", "algorithm_id", "enabled")),
            "clinic_algorithms missing required columns; got: $cols")
    }

    @Test
    fun `AC-1 algorithms table exists with algorithm_key`() {
        val cols = mutableSetOf<String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema='public' AND table_name='algorithms'",
            ).executeQuery().use { rs ->
                while (rs.next()) cols.add(rs.getString(1))
            }
        }
        assertTrue("algorithm_key" in cols, "algorithms.algorithm_key missing; got: $cols")
    }

    // ---------------- Repo behavior ----------------

    @Test
    fun `insertClinic round-trips and findBySlug returns inserted row`() {
        val req = CreateClinicRequest(
            slug = "alpha",
            name = "Alpha",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru", "kk"),
            config = buildJsonObject { put("api_key_hash", JsonPrimitive("h")) },
        )
        val inserted = repo.insertClinic(req)
        assertEquals("alpha", inserted.slug)
        assertEquals("Alpha", inserted.name)
        assertTrue(inserted.id > 0)
        assertEquals(listOf("ru", "kk"), inserted.allowedLanguages)
        assertEquals("h", inserted.config["api_key_hash"]?.jsonPrimitive?.contentOrNull)

        val byslug = repo.findBySlug("alpha")
        assertNotNull(byslug)
        assertEquals(inserted.id, byslug.id)
    }

    @Test
    fun `findBySlug returns null for unknown slug`() {
        assertNull(repo.findBySlug("nope"))
    }

    @Test
    fun `findById returns inserted clinic`() {
        val a = Fixtures.fixtureClinic(repo, slug = "find-by-id")
        val got = repo.findById(a.clinic.id)
        assertNotNull(got)
        assertEquals("find-by-id", got.slug)
    }

    @Test
    fun `updateConfig replaces config json for the given clinic_id`() {
        val a = Fixtures.fixtureClinic(repo, slug = "cfg-update")
        val newCfg = buildJsonObject {
            put("api_key_hash", JsonPrimitive("preserved"))
            put("preferences", buildJsonObject { put("reminder_24h_enabled", JsonPrimitive(false)) })
        }
        repo.updateConfig(a.clinic.id, newCfg)

        val reloaded = repo.findById(a.clinic.id) ?: fail("clinic vanished")
        val prefs = reloaded.config["preferences"]?.jsonObject ?: fail("preferences absent")
        assertEquals("false", prefs["reminder_24h_enabled"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `AC-6 updateConfig with one clinic_id never mutates a different clinic`() {
        val a = Fixtures.fixtureClinic(repo, slug = "iso-a")
        val b = Fixtures.fixtureClinic(repo, slug = "iso-b")

        val newCfg = buildJsonObject { put("touched", JsonPrimitive("only-a")) }
        repo.updateConfig(a.clinic.id, newCfg)

        val reloadedB = repo.findById(b.clinic.id) ?: fail("b vanished")
        assertNull(reloadedB.config["touched"],
            "updateConfig(a.id, ...) must not touch clinic B")
    }

    @Test
    fun `findAlgorithmIdByKey returns null when algorithm absent`() {
        assertNull(repo.findAlgorithmIdByKey("no_such_algo"))
    }

    @Test
    fun `findAlgorithmIdByKey returns the id when algorithm present`() {
        val id = insertAlgorithm("symptom_demo_adult")
        assertEquals(id, repo.findAlgorithmIdByKey("symptom_demo_adult"))
    }

    @Test
    fun `setAlgorithmEnabled inserts and updates clinic_algorithms`() {
        val a = Fixtures.fixtureClinic(repo, slug = "algo-a")
        val algoId = insertAlgorithm("symptom_cough_adult")

        repo.setAlgorithmEnabled(a.clinic.id, algoId, true)
        assertEquals(true, readClinicAlgorithmEnabled(a.clinic.id, algoId))

        repo.setAlgorithmEnabled(a.clinic.id, algoId, false)
        assertEquals(false, readClinicAlgorithmEnabled(a.clinic.id, algoId))

        // Idempotent re-disable
        repo.setAlgorithmEnabled(a.clinic.id, algoId, false)
        assertEquals(false, readClinicAlgorithmEnabled(a.clinic.id, algoId))
    }

    @Test
    fun `AC-6 setAlgorithmEnabled isolates by clinic_id`() {
        val a = Fixtures.fixtureClinic(repo, slug = "algo-iso-a")
        val b = Fixtures.fixtureClinic(repo, slug = "algo-iso-b")
        val algoId = insertAlgorithm("symptom_fever_adult")

        repo.setAlgorithmEnabled(a.clinic.id, algoId, true)
        assertEquals(true, readClinicAlgorithmEnabled(a.clinic.id, algoId))
        assertNull(readClinicAlgorithmEnabled(b.clinic.id, algoId),
            "clinic B must not have a clinic_algorithms row")
    }

    // ---------------- AC-M3WYNS.6 — signature scan ----------------

    @Test
    fun `AC-6 every public ClinicRepo function has clinic_id Long as its first parameter except platform-admin allowlist`() {
        val platformAdminAllowlist = setOf(
            "insertClinic",
            "findBySlug",
            "findAlgorithmIdByKey",
        )

        val kclass = ClinicRepo::class
        val publicFns = kclass.declaredMemberFunctions
            .filter { it.visibility == KVisibility.PUBLIC }

        assertTrue(publicFns.isNotEmpty(), "expected at least one public function on ClinicRepo")

        val violators = mutableListOf<String>()
        for (fn in publicFns) {
            if (fn.name in platformAdminAllowlist) continue
            // parameters[0] is the instance; parameters[1] should be clinic_id: Long
            val params = fn.parameters
            if (params.size < 2) {
                violators.add("${fn.name} has no business parameter (only instance)")
                continue
            }
            val first = params[1]
            val type = first.type.classifier
            val typeOk = type == Long::class
            val nameOk = first.name == "clinicId" || first.name == "clinic_id"
            if (!typeOk || !nameOk) {
                violators.add(
                    "${fn.name}: first business param is name=${first.name} type=$type, " +
                        "expected clinicId: Long",
                )
            }
        }
        assertTrue(violators.isEmpty(),
            "AC-M3WYNS.6 violated; functions not following clinic_id-first convention:\n" +
                violators.joinToString("\n"))
    }

    // ---------------- helpers ----------------

    private fun insertAlgorithm(key: String): Long {
        PostgresTestSupport.database().getConnection().use { conn ->
            // Post-FR-KT4697 the algorithms table requires (algorithm_key, version,
            // metadata, tree); these tests don't exercise the symptomizer payload
            // so we supply minimal valid placeholders.
            conn.prepareStatement(
                "INSERT INTO algorithms (algorithm_key, version, metadata, tree, is_active) " +
                    "VALUES (?, '1.0.0', '{}'::jsonb, '{}'::jsonb, TRUE) RETURNING id",
            ).use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    private fun readClinicAlgorithmEnabled(clinicId: Long, algorithmId: Long): Boolean? {
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT enabled FROM clinic_algorithms WHERE clinic_id=? AND algorithm_id=?",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, algorithmId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getBoolean(1) else null
                }
            }
        }
    }
}
