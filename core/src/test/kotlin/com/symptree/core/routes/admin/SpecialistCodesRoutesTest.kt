package com.symptree.core.routes.admin

import com.symptree.core.Fixtures
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
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

/**
 * FR-XKH348 (backend extension) — `GET /api/v1/admin/specialist-codes` returns
 * the **semantic** specialist codes drawn from the clinic's ENABLED algorithms'
 * conclusion nodes. Powers AC-XKH348.3's API-driven specialist dropdown; the
 * frontend localizes each code to a display label.
 */
class SpecialistCodesRoutesTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private lateinit var algorithmRepo: AlgorithmRepo
    private var clinicAId: Long = 0L
    private var clinicBId: Long = 0L
    private lateinit var tokenA: String
    private lateinit var tokenB: String

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        algorithmRepo = AlgorithmRepo(db)
        clinicAId = Fixtures.fixtureClinic(clinicRepo, slug = "spec-a").clinic.id
        clinicBId = Fixtures.fixtureClinic(clinicRepo, slug = "spec-b").clinic.id
        val svc = AdminFixtures.tokenService()
        tokenA = AdminFixtures.tokenFor(svc, AdminFixtures.seedUser(userRepo, clinicAId, "a@x.io", role = "admin"))
        tokenB = AdminFixtures.tokenFor(svc, AdminFixtures.seedUser(userRepo, clinicBId, "b@x.io", role = "admin"))
    }

    private fun codes(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject["specialist_codes"]!!.jsonArray
            .map { it.jsonPrimitive.content }

    @Test
    fun `returns deduped, sorted specialist codes from the clinic's enabled algorithms`() = testApplication {
        val cardio = algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "alg-cardio", specialist = "cardiologist"),
            "test",
        )
        val ent = algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "alg-ent", specialist = "ent"),
            "test",
        )
        // A duplicate code from another enabled algorithm must collapse.
        val cardio2 = algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "alg-cardio2", specialist = "cardiologist"),
            "test",
        )
        clinicRepo.setAlgorithmEnabled(clinicAId, cardio, enabled = true)
        clinicRepo.setAlgorithmEnabled(clinicAId, ent, enabled = true)
        clinicRepo.setAlgorithmEnabled(clinicAId, cardio2, enabled = true)
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.get("/api/v1/admin/specialist-codes") {
            header("Authorization", "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "got ${resp.bodyAsText()}")
        assertEquals(listOf("cardiologist", "ent"), codes(resp.bodyAsText()), "deduped + sorted semantic codes")
    }

    @Test
    fun `excludes codes from algorithms not enabled for this clinic`() = testApplication {
        val cardio = algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "alg-cardio", specialist = "cardiologist"),
            "test",
        )
        val derm = algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "alg-derm", specialist = "dermatologist"),
            "test",
        )
        // Clinic A enables only cardio; clinic B enables only derm.
        clinicRepo.setAlgorithmEnabled(clinicAId, cardio, enabled = true)
        clinicRepo.setAlgorithmEnabled(clinicBId, derm, enabled = true)
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val a = client.get("/api/v1/admin/specialist-codes") { header("Authorization", "Bearer $tokenA") }
        val b = client.get("/api/v1/admin/specialist-codes") { header("Authorization", "Bearer $tokenB") }
        assertEquals(listOf("cardiologist"), codes(a.bodyAsText()), "clinic A sees only its enabled algorithm's code")
        assertEquals(listOf("dermatologist"), codes(b.bodyAsText()), "clinic B sees only its own")
    }

    @Test
    fun `empty list when the clinic has no enabled algorithms`() = testApplication {
        algorithmRepo.insert(
            AdminFixtures.conclusionAlgorithm(key = "alg-cardio", specialist = "cardiologist"),
            "test",
        )
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/specialist-codes") { header("Authorization", "Bearer $tokenA") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(emptyList(), codes(resp.bodyAsText()))
    }

    @Test
    fun `requires authentication`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/specialist-codes")
        assertEquals(HttpStatusCode.Unauthorized, resp.status, "no bearer -> 401")
    }
}
