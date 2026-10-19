package com.symptree.core.routes.admin

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewDoctor
import com.symptree.core.routes.admin.AdminTestApp.installAdmin
import com.symptree.core.routes.admin.AdminTestApp.jsonClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-MG7TSR.9 — every admin route returns STRUCTURED error bodies (a JSON object
 * carrying a machine `error` code, consistent with `plugins/StatusPages` and the
 * `routes/` ErrorResponse shape), and no user-facing string literal is hardcoded
 * in the `routes/admin/` package (the i18n no-Cyrillic-literal sweep already
 * covers the routes tree; this test pins the package's existence so the coverage is
 * not vacuous).
 */
class AdminErrorContractTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private lateinit var doctorRepo: DoctorRepo
    private var clinicId: Long = 0L
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        doctorRepo = DoctorRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "err-clinic").clinic.id
        adminToken = AdminFixtures.tokenFor(
            AdminFixtures.tokenService(),
            AdminFixtures.seedUser(userRepo, clinicId, "a@x.io", role = "admin"),
        )
    }

    private fun errorCode(body: String): String {
        val obj = Json.parseToJsonElement(body) as? JsonObject
            ?: fail("error body must be a JSON object; got: $body")
        return obj["error"]?.jsonPrimitive?.contentOrNull
            ?: fail("structured error body must carry a machine `error` code; got: $body")
    }

    @Test
    fun `AC-9 a 401 from the admin gate is a structured error object`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/doctors")
        assertTrue(resp.status == HttpStatusCode.Unauthorized)
        val code = errorCode(resp.bodyAsText())
        assertTrue(code.isNotBlank(), "401 must carry a non-blank machine error code")
    }

    @Test
    fun `AC-9 a 422 validation failure is a structured error object`() = testApplication {
        val doctorId = doctorRepo.insert(
            NewDoctor(clinicId, "internal", "err-doc", "Dr Err", listOf("therapist"), 30, true),
        ).id
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.put("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"weekly_hours":[{"day_of_week":1,"start_time":"17:00","end_time":"09:00"}]}""")
        }
        assertTrue(resp.status == HttpStatusCode.UnprocessableEntity, "inverted range must be 422; got ${resp.status}")
        val code = errorCode(resp.bodyAsText())
        assertTrue(code.isNotBlank(), "422 must carry a non-blank machine error code")
    }

    @Test
    fun `AC-9 the routes admin package exists and is swept by the i18n no-literal guard`() {
        // The package must physically exist for the NoHardcodedLiteralsTest sweep
        // (which matches any path containing /routes/) to be non-vacuous here.
        val candidates = listOf(
            File("src/main/kotlin/com/symptree/core/routes/admin"),
            File("core/src/main/kotlin/com/symptree/core/routes/admin"),
        )
        assertTrue(
            candidates.any { it.isDirectory },
            "expected a routes/admin/ source package; tried: ${candidates.map { it.absolutePath }}",
        )
    }
}
