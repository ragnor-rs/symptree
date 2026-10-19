package com.symptree.core.routes.admin

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.routes.admin.AdminTestApp.installAdmin
import com.symptree.core.routes.admin.AdminTestApp.jsonClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-MG7TSR.6 / .8 — symptomizer-results read API + data-subject export/delete.
 *
 * List is clinic-scoped; detail decrypts PII (`platform_user_id`, free-text history)
 * server-side for the authenticated clinic ONLY; cross-clinic access is forbidden.
 * `/export` returns decrypted history; `DELETE` anonymizes (soft, aggregate survives).
 */
class SessionReadRoutesTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private lateinit var sessionRepo: SessionRepo
    private var clinicAId: Long = 0L
    private var clinicBId: Long = 0L
    private lateinit var tokenA: String
    private lateinit var tokenB: String
    private val freeText = "patient says: severe headache for three days"

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        sessionRepo = SessionRepo(db)
        clinicAId = Fixtures.fixtureClinic(clinicRepo, slug = "sess-a").clinic.id
        clinicBId = Fixtures.fixtureClinic(clinicRepo, slug = "sess-b").clinic.id
        val svc = AdminFixtures.tokenService()
        tokenA = AdminFixtures.tokenFor(svc, AdminFixtures.seedUser(userRepo, clinicAId, "a@x.io", role = "admin"))
        tokenB = AdminFixtures.tokenFor(svc, AdminFixtures.seedUser(userRepo, clinicBId, "b@x.io", role = "admin"))
    }

    /**
     * Seed a concluded session whose history carries an ENCRYPTED free-text turn,
     * matching how the symptomizer persists PII (AES-256-GCM via PiiCipher).
     */
    private fun seedSession(clinicId: Long, platformUserId: String): Long {
        val id = sessionRepo.insertActive(clinicId, "TELEGRAM", platformUserId, "ru")
        val encrypted = AdminTestApp.piiCipher.encrypt(freeText)
        // One history turn carrying the encrypted free-text under `user_text_encrypted`.
        val turn = """{"node_id":"q1","user_text_encrypted":"$encrypted","ts":"2026-06-12T09:00:00Z"}"""
        sessionRepo.updateStateAndAppendTurn(id, null, "q1", "concluded", turn)
        return id
    }

    // ---------------- list ----------------

    @Test
    fun `AC-6 list returns this clinic's sessions only`() = testApplication {
        seedSession(clinicAId, "tg-a1")
        seedSession(clinicAId, "tg-a2")
        seedSession(clinicBId, "tg-b1")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.get("/api/v1/admin/sessions") {
            header("Authorization", "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "list must return 200; got ${resp.bodyAsText()}")
        val items = sessionItems(resp.bodyAsText())
        assertEquals(2, items.size, "clinic A must see exactly its own 2 sessions; got ${resp.bodyAsText()}")
    }

    @Test
    fun `AC-6 list does NOT leak full decrypted free-text - redacted summary only`() = testApplication {
        seedSession(clinicAId, "tg-a1")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/sessions") {
            header("Authorization", "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(
            !resp.bodyAsText().contains(freeText),
            "the bulk list must not expose the full decrypted free-text history",
        )
    }

    // ---------------- detail (decrypt) ----------------

    @Test
    fun `AC-6 detail decrypts the free-text history for the owning clinic`() = testApplication {
        val sid = seedSession(clinicAId, "tg-a1")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/sessions/$sid") {
            header("Authorization", "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "detail must return 200; got ${resp.bodyAsText()}")
        assertTrue(
            resp.bodyAsText().contains(freeText),
            "detail must decrypt the history free-text server-side for the owning clinic",
        )
    }

    @Test
    fun `AC-6 detail for another clinic's session returns 403, never the plaintext`() = testApplication {
        val sidA = seedSession(clinicAId, "tg-a1")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        // Clinic B's token addressing clinic A's session id.
        val resp = client.get("/api/v1/admin/sessions/$sidA") {
            header("Authorization", "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status,
            "cross-clinic session access must be 403 (not 404 / not 200)")
        assertTrue(!resp.bodyAsText().contains(freeText), "must never leak another clinic's plaintext")
    }

    // ---------------- export / delete (AC-8) ----------------

    @Test
    fun `AC-8 export returns the decrypted session history as JSON`() = testApplication {
        val sid = seedSession(clinicAId, "tg-a1")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/sessions/$sid/export") {
            header("Authorization", "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "export must return 200; got ${resp.bodyAsText()}")
        assertTrue(resp.bodyAsText().contains(freeText), "export must contain the decrypted history")
    }

    @Test
    fun `AC-8 delete anonymizes - row survives, PII gone`() = testApplication {
        val sid = seedSession(clinicAId, "tg-a1")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.delete("/api/v1/admin/sessions/$sid") {
            header("Authorization", "Bearer $tokenA")
        }
        assertTrue(resp.status.value in 200..299, "delete (anonymize) must succeed; got ${resp.status}")

        // Not a hard delete: the aggregate row survives.
        val rowSurvives = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT platform_user_id, history FROM sessions WHERE id = ?").use { ps ->
                ps.setLong(1, sid)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    rs.getString("platform_user_id") to rs.getString("history")
                }
            }
        } ?: fail("anonymize must NOT hard-delete the row (aggregate must survive)")

        val (platformUserId, history) = rowSurvives
        assertTrue(
            !history.contains(freeText) && !platformUserId.contains("tg-a1"),
            "after anonymization no PII (platform_user_id, free-text) may remain; saw uid=$platformUserId history=$history",
        )
    }

    @Test
    fun `AC-8 operator may not delete a session - admin role required`() = testApplication {
        val sid = seedSession(clinicAId, "tg-a1")
        val operatorToken = AdminFixtures.tokenFor(
            AdminFixtures.tokenService(),
            AdminFixtures.seedUser(userRepo, clinicAId, "op@x.io", role = "operator"),
        )
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.delete("/api/v1/admin/sessions/$sid") {
            header("Authorization", "Bearer $operatorToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status, "data-subject delete requires admin role")
    }

    private fun sessionItems(body: String): JsonArray {
        val element = Json.parseToJsonElement(body)
        if (element is JsonArray) return element
        return element.jsonObject["sessions"]?.jsonArray
            ?: element.jsonObject["items"]?.jsonArray
            ?: fail("expected a sessions/items array; got $body")
    }
}
