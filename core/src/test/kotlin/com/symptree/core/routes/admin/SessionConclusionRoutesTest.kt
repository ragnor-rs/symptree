package com.symptree.core.routes.admin

import com.symptree.core.Fixtures
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.routes.admin.AdminTestApp.installAdmin
import com.symptree.core.routes.admin.AdminTestApp.jsonClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-XKH348 (backend extension) — the admin session-DETAIL endpoint surfaces a
 * **semantic** conclusion block so the SPA can render the triage outcome
 * (urgency badge, recommended specialist, tests + prep) without owning the
 * algorithm tree.
 *
 * Separation of concerns (FR-VWRNDR): the wire carries the semantic [urgency]
 * enum + the [specialist] code + algorithm-authored `diagnosis`/`action`/`tests`
 * text localized by core to the session language. No chrome, no UI labels.
 */
class SessionConclusionRoutesTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private lateinit var sessionRepo: SessionRepo
    private lateinit var algorithmRepo: AlgorithmRepo
    private var clinicAId: Long = 0L
    private lateinit var tokenA: String

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        sessionRepo = SessionRepo(db)
        algorithmRepo = AlgorithmRepo(db)
        clinicAId = Fixtures.fixtureClinic(clinicRepo, slug = "concl-a").clinic.id
        val svc = AdminFixtures.tokenService()
        tokenA = AdminFixtures.tokenFor(svc, AdminFixtures.seedUser(userRepo, clinicAId, "a@x.io", role = "admin"))
    }

    /** Seed a concluded session pointing at [algorithmId]'s `concl` conclusion node. */
    private fun seedConcluded(algorithmId: Long): Long {
        val id = sessionRepo.insertActive(clinicAId, "TELEGRAM", "tg-a1", "ru")
        val turn = """{"node_id":"concl","ts":"2026-06-12T09:00:00Z"}"""
        sessionRepo.updateStateAndAppendTurn(id, algorithmId, "concl", "concluded", turn)
        return id
    }

    @Test
    fun `concluded session detail carries a semantic conclusion block`() = testApplication {
        val algoId = algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "concl-algo", specialist = "cardiologist", tests = listOf("ecg")),
            "test",
        )
        val sid = seedConcluded(algoId)
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.get("/api/v1/admin/sessions/$sid") {
            header("Authorization", "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "detail must return 200; got ${resp.bodyAsText()}")
        val conclusion = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["conclusion"]?.jsonObject
            ?: error("session detail must carry a `conclusion` object; got ${resp.bodyAsText()}")

        assertEquals("urgent", conclusion["urgency"]?.jsonPrimitive?.content, "semantic urgency enum")
        assertEquals("cardiologist", conclusion["specialist"]?.jsonPrimitive?.content, "semantic specialist code")
        assertEquals("book_appointment", conclusion["clinic_action"]?.jsonPrimitive?.content, "semantic clinic action")
        // Algorithm-authored text localized to the session language (ru).
        assertEquals(
            "Срочный диагноз",
            conclusion["diagnosis"]?.jsonPrimitive?.content,
            "diagnosis localized to the session language",
        )
        val tests = conclusion["tests"]?.jsonArray ?: error("tests array expected")
        assertEquals(1, tests.size, "one recommended test")
        assertEquals("ecg", tests[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("Натощак", tests[0].jsonObject["prep"]?.jsonPrimitive?.content, "localized prep note")
    }

    @Test
    fun `conclusion carries no backend-localized chrome - only semantic data`() = testApplication {
        val algoId = algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "concl-algo", specialist = "ent"),
            "test",
        )
        val sid = seedConcluded(algoId)
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/sessions/$sid") {
            header("Authorization", "Bearer $tokenA")
        }
        // Urgency is a code, never a rendered label like "Срочно" / "Urgent".
        assertTrue(resp.bodyAsText().contains("\"urgency\":\"urgent\""), "urgency must be the semantic code")
    }

    @Test
    fun `active (not concluded) session detail has a null conclusion`() = testApplication {
        val sid = sessionRepo.insertActive(clinicAId, "TELEGRAM", "tg-a2", "ru")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/sessions/$sid") {
            header("Authorization", "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val conclusion = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["conclusion"]
        assertTrue(
            conclusion == null || conclusion is kotlinx.serialization.json.JsonNull,
            "an un-concluded session must not carry a conclusion; got ${resp.bodyAsText()}",
        )
    }

    @Test
    fun `conclusion is null when the terminal node is not a conclusion`() = testApplication {
        // status concluded but current node points at the question node (defensive).
        val algoId = algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "concl-algo", specialist = "ent"),
            "test",
        )
        val sid = sessionRepo.insertActive(clinicAId, "TELEGRAM", "tg-a3", "ru")
        sessionRepo.updateStateAndAppendTurn(sid, algoId, "q1", "concluded", """{"node_id":"q1"}""")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/sessions/$sid") {
            header("Authorization", "Bearer $tokenA")
        }
        assertNull(
            Json.parseToJsonElement(resp.bodyAsText()).jsonObject["conclusion"]?.takeIf {
                it !is kotlinx.serialization.json.JsonNull
            },
            "a non-conclusion terminal node yields no conclusion block",
        )
    }
}
