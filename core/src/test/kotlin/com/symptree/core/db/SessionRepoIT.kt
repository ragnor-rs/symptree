package com.symptree.core.db

import com.symptree.core.Fixtures
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.symptomizer.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-PWVTT4.1 — sessions table exists + UNIQUE(platform, platform_user_id,
 * clinic_id, status) is enforced. Migration `database/03-migrate-sessions.sql`
 * applies cleanly on top of 01 + 02.
 *
 * Also exercises `SessionRepo` insert/find/history-round-trip so the repo
 * is wired against the table from day one.
 */
class SessionRepoIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var sessionRepo: SessionRepo

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        clinicRepo = ClinicRepo(PostgresTestSupport.database())
        sessionRepo = SessionRepo(PostgresTestSupport.database())
    }

    @Test
    fun `AC-1 migration file 03-migrate-sessions sql exists at repo root`() {
        val candidates = listOf(
            File("database/03-migrate-sessions.sql"),
            File("../database/03-migrate-sessions.sql"),
        )
        val migration = candidates.firstOrNull { it.isFile }
        assertNotNull(migration, "expected database/03-migrate-sessions.sql to exist; tried $candidates")
    }

    @Test
    fun `AC-1 sessions table has the columns from technical-spec 2 dot 1`() {
        val expected = mapOf(
            "id" to "bigint",
            "clinic_id" to "bigint",
            "platform" to "text",
            "platform_user_id" to "text",
            "language" to "text",
            "current_algorithm_id" to "bigint",
            "current_node_id" to "text",
            "history" to "jsonb",
            "status" to "text",
            "retention_anonymize_at" to "timestamp with time zone",
            "created_at" to "timestamp with time zone",
            "updated_at" to "timestamp with time zone",
        )
        val seen = mutableMapOf<String, String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = 'sessions'",
            ).executeQuery().use { rs ->
                while (rs.next()) seen[rs.getString(1)] = rs.getString(2)
            }
        }
        for ((col, type) in expected) {
            assertEquals(type, seen[col], "sessions.$col expected $type, got ${seen[col]} (all: $seen)")
        }
    }

    @Test
    fun `AC-1 sessions enforces UNIQUE on platform, platform_user_id, clinic_id, status`() {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "uniqct")
        sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-42",
            language = "ru",
        )
        try {
            sessionRepo.insertActive(
                clinicId = clinic.id,
                platform = "telegram",
                platformUserId = "tg-42",
                language = "ru",
            )
            fail("expected unique-violation for duplicate active session")
        } catch (e: Exception) {
            val msg = (e.message ?: "") + (e.cause?.message ?: "")
            assertTrue(
                msg.contains("unique", ignoreCase = true) || msg.contains("duplicate", ignoreCase = true),
                "expected unique-violation; got: $msg",
            )
        }
    }

    @Test
    fun `AC-1 history JSONB round-trips with encrypted user_text payload`() {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "jsonbct")
        val sessionId = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-99",
            language = "ru",
        )

        val encryptedB64 = Base64.getEncoder().encodeToString("ciphertext-bytes".toByteArray())
        val newHistory: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("turn", JsonPrimitive(1))
                    put("node_id", JsonPrimitive("q1"))
                    put("user_text_encrypted", JsonPrimitive(encryptedB64))
                    put("llm_intent", JsonPrimitive("SYMPTOM"))
                    put("picked_option", JsonPrimitive("yes"))
                    put("ts", JsonPrimitive("2026-06-02T10:00:00Z"))
                },
            )
        }
        sessionRepo.replaceHistory(sessionId, newHistory)

        val loaded = sessionRepo.findById(sessionId)
            ?: fail("session should exist after insert")
        val loadedAsSession: Session = loaded
        assertEquals(clinic.id, loadedAsSession.clinicId)

        // Read raw history JSONB and assert structure.
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT history::text FROM sessions WHERE id = ?").use { ps ->
                ps.setLong(1, sessionId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val raw = rs.getString(1)
                    val parsed = Json.parseToJsonElement(raw).jsonArray
                    assertEquals(1, parsed.size)
                    val first = parsed[0].jsonObject
                    assertEquals(encryptedB64, first["user_text_encrypted"]!!.jsonPrimitive.content)
                    assertEquals("SYMPTOM", first["llm_intent"]!!.jsonPrimitive.content)
                    assertEquals("yes", first["picked_option"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun `AC-1 multiple concluded sessions for the same tuple coexist (multi-walk regression)`() {
        // Regression for the production incident at 2026-06-03 21:07: a patient
        // who completes the symptomizer twice would hit a UNIQUE violation on
        // the second conclusion because the original 03-migrate-sessions.sql
        // declared UNIQUE(platform, platform_user_id, clinic_id, status) which
        // also blocked having two concluded rows for the same patient.
        // 04-migrate-sessions-active-only-unique.sql replaces the broad
        // constraint with a partial unique index restricted to status='active'.
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "multi-concl")
        val first = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-replay",
            language = "ru",
        )
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("UPDATE sessions SET status = 'concluded' WHERE id = ?").use { ps ->
                ps.setLong(1, first)
                ps.executeUpdate()
            }
        }
        val second = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-replay",
            language = "ru",
        )
        // Second walk reaches its own conclusion — should NOT violate the
        // partial unique index because uniqueness now only applies to active rows.
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("UPDATE sessions SET status = 'concluded' WHERE id = ?").use { ps ->
                ps.setLong(1, second)
                ps.executeUpdate()
            }
        }
        // Both rows exist with status='concluded' — no constraint violation.
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM sessions WHERE platform='telegram' AND " +
                    "platform_user_id='tg-replay' AND clinic_id=? AND status='concluded'",
            ).use { ps ->
                ps.setLong(1, clinic.id)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt(1), "expected two concluded rows to coexist")
                }
            }
        }
    }

    @Test
    fun `AC-1 a concluded session and a new active session for the same tuple coexist`() {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "twostates")
        val active = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-7",
            language = "ru",
        )
        // Manually flip first row to concluded so we can insert a fresh active one.
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("UPDATE sessions SET status = 'concluded' WHERE id = ?").use { ps ->
                ps.setLong(1, active)
                ps.executeUpdate()
            }
        }
        // Should succeed — the UNIQUE is on (platform, platform_user_id, clinic_id, status), not on the tuple alone.
        val second = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-7",
            language = "ru",
        )
        assertTrue(second > active)
    }

    @Test
    fun `cancelActive flips an active session to cancelled and returns 1`() {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "cancel-active")
        val sessionId = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-cancel",
            language = "ru",
        )

        val rowsAffected = sessionRepo.cancelActive(sessionId)
        assertEquals(1, rowsAffected)

        val loaded = sessionRepo.findById(sessionId) ?: fail("session vanished")
        assertEquals("cancelled", loaded.status)
    }

    @Test
    fun `cancelActive is idempotent and returns 0 when session already non-active`() {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "cancel-idemp")
        val sessionId = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-idemp",
            language = "ru",
        )
        sessionRepo.cancelActive(sessionId)

        // Second cancel — the row is already cancelled.
        val rowsAffected = sessionRepo.cancelActive(sessionId)
        assertEquals(0, rowsAffected, "second cancel must be a no-op without raising")
    }

    @Test
    fun `cancelActive permits a fresh active session for the same tuple immediately after`() {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "cancel-reuse")
        val first = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-reuse",
            language = "ru",
        )
        sessionRepo.cancelActive(first)

        // Partial-unique-index contract: a NEW active session for the same
        // tuple must succeed because the cancelled row is no longer in the
        // 'active' subset of the unique index.
        val second = sessionRepo.insertActive(
            clinicId = clinic.id,
            platform = "telegram",
            platformUserId = "tg-reuse",
            language = "ru",
        )
        assertTrue(second > first)
    }

    @Test
    fun `AC-MG7TSR8 anonymize scrubs PII and two active sessions in one clinic do not collide`() {
        // Two DISTINCT active patients in the same (clinic, platform) — both rows
        // sit in the 'active' subset of the sessions_active_unique partial index.
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "anon-collide")
        val first = sessionRepo.insertActive(clinic.id, "telegram", "tg-anon-1", "ru")
        val second = sessionRepo.insertActive(clinic.id, "telegram", "tg-anon-2", "ru")

        // Anonymizing both must NOT collide: a constant placeholder would make both
        // rows '(telegram, anonymized, clinic)' and violate the active-unique index.
        assertEquals(1, sessionRepo.anonymize(clinic.id, first))
        assertEquals(1, sessionRepo.anonymize(clinic.id, second),
            "second anonymize must not collide on the active-unique index")

        // PII is gone; the aggregate rows survive with distinct placeholders.
        val a = sessionRepo.findById(first) ?: fail("first row hard-deleted")
        val b = sessionRepo.findById(second) ?: fail("second row hard-deleted")
        assertTrue(!a.platformUserId.contains("tg-anon-1"), "row1 platform_user_id PII must be scrubbed")
        assertTrue(!b.platformUserId.contains("tg-anon-2"), "row2 platform_user_id PII must be scrubbed")
        assertTrue(a.platformUserId != b.platformUserId, "placeholders must stay distinct per row")
    }
}
