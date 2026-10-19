package com.symptree.core.integration

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.model.NewDoctor
import com.symptree.core.routes.admin.AdminFixtures
import com.symptree.core.routes.admin.AdminTestApp
import com.symptree.core.routes.admin.AdminTestApp.installAdmin
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * AC-MG7TSR.3 — cross-tenant sweep for the admin REST surface.
 *
 * `clinic_id` is taken from the TOKEN, never from a path/body param, so a token
 * minted for clinic B addressing clinic A's resource ids MUST return 403 — never
 * 404 (existence leak) and never 200 — on every admin route that
 * carries a resource id.
 */
class AdminMultiTenancySweepIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private lateinit var doctorRepo: DoctorRepo
    private lateinit var sessionRepo: SessionRepo

    private var clinicAId: Long = 0L
    private var clinicBId: Long = 0L
    private lateinit var tokenB: String

    // Clinic-A resource ids (addressed with clinic-B's token).
    private var doctorAId: Long = 0L
    private var sessionAId: Long = 0L
    private var bookingAId: Long = 0L

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        doctorRepo = DoctorRepo(db)
        sessionRepo = SessionRepo(db)

        clinicAId = Fixtures.fixtureClinic(clinicRepo, slug = "sweep-a").clinic.id
        clinicBId = Fixtures.fixtureClinic(clinicRepo, slug = "sweep-b").clinic.id
        tokenB = AdminFixtures.tokenFor(
            AdminFixtures.tokenService(),
            AdminFixtures.seedUser(userRepo, clinicBId, "b@x.io", role = "admin"),
        )

        val doc = doctorRepo.insert(NewDoctor(clinicAId, "internal", "a-doc", "Dr A", listOf("therapist"), 30, true))
        doctorAId = doc.id
        sessionAId = sessionRepo.insertActive(clinicAId, "TELEGRAM", "tg-a", "ru")
        bookingAId = db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO bookings (clinic_id, session_id, source_id, external_doctor_id, " +
                    "doctor_identity_id, specialist_code, slot_at, slot_end, external_appointment_id, status) " +
                    "VALUES (?, ?, 'internal', 'a-doc', ?, 'therapist', ?, ?, NULL, 'confirmed') RETURNING id",
            ).use { ps ->
                ps.setLong(1, clinicAId)
                ps.setLong(2, sessionAId)
                ps.setLong(3, doc.identityId)
                ps.setTimestamp(4, Timestamp.from(Instant.parse("2026-07-07T10:00:00Z")))
                ps.setTimestamp(5, Timestamp.from(Instant.parse("2026-07-07T10:30:00Z")))
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }

    private data class RouteCase(
        val label: String,
        val method: HttpMethod,
        val url: String,
        val body: String? = null,
    )

    private fun routes(): List<RouteCase> = listOf(
        RouteCase("GET doctor (via list scoping)", HttpMethod.Patch, "/api/v1/admin/doctors/$doctorAId",
            """{"full_name":"hijack"}"""),
        RouteCase("DELETE doctor", HttpMethod.Delete, "/api/v1/admin/doctors/$doctorAId"),
        RouteCase("PUT weekly-hours", HttpMethod.Put, "/api/v1/admin/doctors/$doctorAId/weekly-hours",
            """{"weekly_hours":[{"day_of_week":1,"start_time":"09:00","end_time":"17:00"}]}"""),
        RouteCase("POST time-off", HttpMethod.Post, "/api/v1/admin/doctors/$doctorAId/time-off",
            """{"start_at":"2026-07-01T09:00:00Z","end_at":"2026-07-01T18:00:00Z"}"""),
        RouteCase("GET time-off", HttpMethod.Get, "/api/v1/admin/doctors/$doctorAId/time-off"),
        RouteCase("GET session detail", HttpMethod.Get, "/api/v1/admin/sessions/$sessionAId"),
        RouteCase("GET session export", HttpMethod.Get, "/api/v1/admin/sessions/$sessionAId/export"),
        RouteCase("DELETE session (anonymize)", HttpMethod.Delete, "/api/v1/admin/sessions/$sessionAId"),
        RouteCase("PATCH booking cancel", HttpMethod.Patch, "/api/v1/admin/bookings/$bookingAId",
            """{"action":"cancel","reason":"hijack"}"""),
    )

    @Test
    fun `AC-3 every admin route with a resource id returns 403 for a foreign token`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val violations = mutableListOf<String>()
        for (case in routes()) {
            val resp: HttpResponse = client.request(case.url) {
                method = case.method
                header("Authorization", "Bearer $tokenB")
                if (case.body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(case.body)
                }
            }
            if (resp.status != HttpStatusCode.Forbidden) {
                violations.add("${case.label} (${case.method.value} ${case.url}) returned ${resp.status}, expected 403")
            }
        }
        assertEquals(emptyList(), violations,
            "AC-MG7TSR.3: every cross-tenant admin call must be 403:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `AC-3 cross-tenant admin responses never leak existence as 404 and never succeed as 200`() = testApplication {
        installAdmin(AdminTestApp.deps())
        for (case in routes()) {
            val resp = client.request(case.url) {
                method = case.method
                header("Authorization", "Bearer $tokenB")
                if (case.body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(case.body)
                }
            }
            assertNotEquals(HttpStatusCode.NotFound, resp.status, "${case.label}: leaks existence as 404")
            assertNotEquals(HttpStatusCode.OK, resp.status, "${case.label}: succeeds as 200 with a foreign token")
        }
    }
}
