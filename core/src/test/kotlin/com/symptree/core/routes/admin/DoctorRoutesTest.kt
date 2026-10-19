package com.symptree.core.routes.admin

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewDoctor
import com.symptree.core.routes.admin.AdminTestApp.installAdmin
import com.symptree.core.routes.admin.AdminTestApp.jsonClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-MG7TSR.4 — Doctor CRUD under `/api/v1/admin/doctors`.
 *
 * create (auto-creates the singleton doctor_identity), list (active + soft-deleted
 * filter), update mutable fields, soft-delete; admin writes / operator read-only.
 */
class DoctorRoutesTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private lateinit var doctorRepo: DoctorRepo
    private var clinicId: Long = 0L
    private lateinit var adminToken: String
    private lateinit var operatorToken: String

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        doctorRepo = DoctorRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "doc-clinic").clinic.id
        val svc = AdminFixtures.tokenService()
        adminToken = AdminFixtures.tokenFor(svc, AdminFixtures.seedUser(userRepo, clinicId, "a@x.io", role = "admin"))
        operatorToken = AdminFixtures.tokenFor(svc, AdminFixtures.seedUser(userRepo, clinicId, "o@x.io", role = "operator"))
    }

    private fun seedDoctor(externalId: String, name: String, active: Boolean = true): Long =
        doctorRepo.insert(
            NewDoctor(clinicId, "internal", externalId, name, listOf("therapist"), 30, active),
        ).id

    // ---------------- create ----------------

    @Test
    fun `AC-4 admin creates a doctor - 201 and a singleton doctor_identity exists`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.post("/api/v1/admin/doctors") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"full_name":"Dr Created","specialist_codes":["therapist"],"slot_minutes":20}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status, "create must return 201; got ${resp.bodyAsText()}")
        val created = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val id = created["id"]?.jsonPrimitive?.contentOrNull?.toLong() ?: fail("response must carry the new id")

        // The created doctor must be persisted under THIS clinic with a non-null identity.
        val persisted = doctorRepo.findById(clinicId, id) ?: fail("doctor not persisted")
        assertEquals("Dr Created", persisted.fullName)
        assertTrue(persisted.identityId > 0, "create must auto-create the singleton doctor_identity")
    }

    @Test
    fun `AC-4 operator cannot create a doctor - 403`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.post("/api/v1/admin/doctors") {
            header("Authorization", "Bearer $operatorToken")
            contentType(ContentType.Application.Json)
            setBody("""{"full_name":"Dr Nope","specialist_codes":["therapist"],"slot_minutes":30}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ---------------- list ----------------

    @Test
    fun `AC-4 list returns active doctors and operator may read`() = testApplication {
        seedDoctor("d1", "Dr One")
        seedDoctor("d2", "Dr Two")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/doctors") {
            header("Authorization", "Bearer $operatorToken")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "operator must be allowed to read doctors")
        val names = doctorNames(resp.bodyAsText())
        assertTrue(names.containsAll(listOf("Dr One", "Dr Two")), "list must include active doctors; got $names")
    }

    @Test
    fun `AC-4 list with soft-deleted filter excludes vs includes deleted rows`() = testApplication {
        val liveId = seedDoctor("live", "Dr Live")
        val goneId = seedDoctor("gone", "Dr Gone")
        doctorRepo.softDelete(clinicId, goneId)
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        // Default list excludes the soft-deleted doctor.
        val def = client.get("/api/v1/admin/doctors") {
            header("Authorization", "Bearer $adminToken")
        }
        val defaultNames = doctorNames(def.bodyAsText())
        assertTrue("Dr Live" in defaultNames, "live doctor must appear by default")
        assertTrue("Dr Gone" !in defaultNames, "soft-deleted doctor must be hidden by default")

        // Explicit include surfaces the soft-deleted doctor.
        val incl = client.get("/api/v1/admin/doctors?include_deleted=true") {
            header("Authorization", "Bearer $adminToken")
        }
        val inclNames = doctorNames(incl.bodyAsText())
        assertTrue("Dr Gone" in inclNames, "include_deleted=true must surface soft-deleted doctors; got $inclNames")
        assertTrue(liveId > 0 && goneId > 0)
    }

    // ---------------- update ----------------

    @Test
    fun `AC-4 admin updates mutable fields - 200 and persists`() = testApplication {
        val id = seedDoctor("upd", "Dr Old")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.patch("/api/v1/admin/doctors/$id") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"full_name":"Dr New","specialist_codes":["cardiologist"],"slot_minutes":45,"active":false}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "update must return 200; got ${resp.bodyAsText()}")

        val persisted = doctorRepo.findById(clinicId, id) ?: fail("doctor vanished")
        assertEquals("Dr New", persisted.fullName)
        assertEquals(listOf("cardiologist"), persisted.specialistCodes)
        assertEquals(45, persisted.slotMinutes)
        assertEquals(false, persisted.active)
    }

    // ---------------- delete (soft) ----------------

    @Test
    fun `AC-4 delete soft-deletes the doctor - subsequent active list omits it`() = testApplication {
        val id = seedDoctor("del", "Dr Delete")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.delete("/api/v1/admin/doctors/$id") {
            header("Authorization", "Bearer $adminToken")
        }
        assertTrue(resp.status.value in 200..299, "delete must succeed; got ${resp.status}")

        // Soft delete: the row still exists but has deleted_at set, so listActive omits it.
        assertTrue(doctorRepo.listActive(clinicId).none { it.id == id },
            "soft-deleted doctor must drop out of the active list")
        val deletedAt = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT deleted_at FROM doctors WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs -> rs.next(); rs.getTimestamp("deleted_at") }
            }
        }
        assertTrue(deletedAt != null, "soft-delete must set deleted_at, not hard-delete the row")
    }

    private fun doctorNames(body: String): List<String> {
        val element = Json.parseToJsonElement(body)
        val arr: JsonArray = if (element is JsonArray) {
            element
        } else {
            element.jsonObject["doctors"]?.jsonArray ?: fail("expected a doctors array; got $body")
        }
        return arr.map { it.jsonObject["full_name"]?.jsonPrimitive?.contentOrNull ?: "" }
    }
}
