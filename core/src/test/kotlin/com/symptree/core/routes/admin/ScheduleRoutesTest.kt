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
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-MG7TSR.5 — Schedule CRUD.
 *
 * `PUT /api/v1/admin/doctors/{id}/weekly-hours` replaces the doctor's weekly-hours
 * set (split shifts allowed); `POST/GET/DELETE .../time-off`. Inverted ranges and
 * overlapping weekly-hours rows for the same weekday are rejected with 422.
 */
class ScheduleRoutesTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private lateinit var doctorRepo: DoctorRepo
    private var clinicId: Long = 0L
    private var doctorId: Long = 0L
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        doctorRepo = DoctorRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "sched-clinic").clinic.id
        doctorId = doctorRepo.insert(
            NewDoctor(clinicId, "internal", "sched-doc", "Dr Sched", listOf("therapist"), 30, true),
        ).id
        adminToken = AdminFixtures.tokenFor(
            AdminFixtures.tokenService(),
            AdminFixtures.seedUser(userRepo, clinicId, "a@x.io", role = "admin"),
        )
    }

    private fun auth() = "Bearer $adminToken"

    // ---------------- weekly-hours replace ----------------

    @Test
    fun `AC-5 PUT weekly-hours replaces the set and allows split shifts`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        // Two rows for Monday (day_of_week=1) = a split shift.
        val resp = client.put("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", auth())
            contentType(ContentType.Application.Json)
            setBody(
                """{"weekly_hours":[
                   {"day_of_week":1,"start_time":"09:00","end_time":"12:00"},
                   {"day_of_week":1,"start_time":"13:00","end_time":"17:00"}
                ]}""".trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, "replace must return 200; got ${resp.bodyAsText()}")

        val rows = doctorRepo.weeklyHours(doctorId)
        assertEquals(2, rows.size, "split shift must persist two Monday rows")
        assertTrue(rows.all { it.dayOfWeek == 1 })
    }

    @Test
    fun `AC-5 PUT weekly-hours fully replaces - prior rows are gone`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        client.put("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", auth())
            contentType(ContentType.Application.Json)
            setBody("""{"weekly_hours":[{"day_of_week":2,"start_time":"08:00","end_time":"16:00"}]}""")
        }
        // Replace with a different day; the Tuesday row must disappear.
        client.put("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", auth())
            contentType(ContentType.Application.Json)
            setBody("""{"weekly_hours":[{"day_of_week":3,"start_time":"08:00","end_time":"16:00"}]}""")
        }
        val days = doctorRepo.weeklyHours(doctorId).map { it.dayOfWeek }.toSet()
        assertEquals(setOf(3), days, "replace must drop the prior Tuesday row, leaving only Wednesday")
    }

    @Test
    fun `AC-5 PUT weekly-hours with an inverted range returns 422`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.put("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", auth())
            contentType(ContentType.Application.Json)
            setBody("""{"weekly_hours":[{"day_of_week":1,"start_time":"17:00","end_time":"09:00"}]}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status,
            "end_time must be > start_time -> 422")
    }

    @Test
    fun `AC-5 PUT weekly-hours with overlapping rows on the same weekday returns 422`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.put("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", auth())
            contentType(ContentType.Application.Json)
            setBody(
                """{"weekly_hours":[
                   {"day_of_week":1,"start_time":"09:00","end_time":"13:00"},
                   {"day_of_week":1,"start_time":"12:00","end_time":"17:00"}
                ]}""".trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status,
            "overlapping weekly-hours rows on the same weekday must be rejected with 422")
        // And nothing must be persisted on a rejected replace.
        assertTrue(doctorRepo.weeklyHours(doctorId).isEmpty(),
            "a rejected replace must not partially persist")
    }

    // ---------------- weekly-hours read (FR-XKH348) ----------------

    @Test
    fun `XKH348 GET weekly-hours returns the persisted set`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        // Seed via the replace endpoint: a Sunday (day 0) + a Monday split shift.
        client.put("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", auth())
            contentType(ContentType.Application.Json)
            setBody(
                """{"weekly_hours":[
                   {"day_of_week":0,"start_time":"10:00","end_time":"14:00"},
                   {"day_of_week":1,"start_time":"09:00","end_time":"12:00"},
                   {"day_of_week":1,"start_time":"13:00","end_time":"17:00"}
                ]}""".trimIndent(),
            )
        }

        val resp = client.get("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", auth())
        }
        assertEquals(HttpStatusCode.OK, resp.status, "GET weekly-hours must return 200; got ${resp.bodyAsText()}")
        val body = resp.bodyAsText()
        // Same wire shape as the PUT body: a weekly_hours array of {day_of_week,start_time,end_time}.
        assertTrue(body.contains("\"day_of_week\":0"), "must include the Sunday (day 0) row; got $body")
        assertTrue(body.contains("\"start_time\":\"10:00\""), "must echo the Sunday start time; got $body")
        assertEquals(
            3,
            Regex("\"day_of_week\"").findAll(body).count(),
            "must return all three persisted rows; got $body",
        )
    }

    @Test
    fun `XKH348 GET weekly-hours for another clinic's doctor returns 403`() = testApplication {
        val otherClinic = Fixtures.fixtureClinic(clinicRepo, slug = "sched-other").clinic.id
        val otherToken = AdminFixtures.tokenFor(
            AdminFixtures.tokenService(),
            AdminFixtures.seedUser(userRepo, otherClinic, "other@x.io", role = "admin"),
        )
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/doctors/$doctorId/weekly-hours") {
            header("Authorization", "Bearer $otherToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status,
            "cross-clinic weekly-hours read must be 403 (not 404/200)")
    }

    // ---------------- time-off ----------------

    @Test
    fun `AC-5 POST then GET time-off round-trips`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val post = client.post("/api/v1/admin/doctors/$doctorId/time-off") {
            header("Authorization", auth())
            contentType(ContentType.Application.Json)
            setBody("""{"start_at":"2026-07-01T09:00:00Z","end_at":"2026-07-01T18:00:00Z","reason":"leave"}""")
        }
        assertTrue(post.status.value in 200..299, "create time-off must succeed; got ${post.status} ${post.bodyAsText()}")
        assertEquals(1, doctorRepo.timeOff(doctorId).size, "time-off row must persist")

        val get = client.get("/api/v1/admin/doctors/$doctorId/time-off") {
            header("Authorization", auth())
        }
        assertEquals(HttpStatusCode.OK, get.status)
        assertTrue(get.bodyAsText().contains("2026-07-01"), "GET must list the created window")
    }

    @Test
    fun `AC-5 POST time-off with end_at not after start_at returns 422`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.post("/api/v1/admin/doctors/$doctorId/time-off") {
            header("Authorization", auth())
            contentType(ContentType.Application.Json)
            setBody("""{"start_at":"2026-07-01T18:00:00Z","end_at":"2026-07-01T09:00:00Z"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status,
            "end_at must be > start_at -> 422")
        assertTrue(doctorRepo.timeOff(doctorId).isEmpty(), "rejected time-off must not persist")
    }
}
