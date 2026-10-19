package com.symptree.core.routes.admin

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewDoctor
import com.symptree.core.routes.admin.AdminTestApp.installAdmin
import com.symptree.core.routes.admin.AdminTestApp.jsonClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-MG7TSR.7 — booking management.
 *
 * `GET .../bookings` lists with doctor display name + slot + status + feedback;
 * `PATCH .../bookings/{id}` with `{action:"cancel"}` cancels, pushes a semantic
 * booking-cancelled notification to the patient, and frees the slot. Re-cancel -> 409.
 */
class BookingAdminRoutesTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private lateinit var doctorRepo: DoctorRepo
    private lateinit var sessionRepo: SessionRepo
    private var clinicId: Long = 0L
    private var identityId: Long = 0L
    private var sessionId: Long = 0L
    private lateinit var adminToken: String

    private val slotAt = Instant.parse("2026-07-07T10:00:00Z")
    private val slotEnd = Instant.parse("2026-07-07T10:30:00Z")
    private val patientUserId = "tg-patient"

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        doctorRepo = DoctorRepo(db)
        sessionRepo = SessionRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "bk-clinic", defaultLanguage = "ru").clinic.id
        val doc = doctorRepo.insert(
            NewDoctor(clinicId, "internal", "bk-doc", "Доктор Иванова", listOf("therapist"), 30, true),
        )
        identityId = doc.identityId
        sessionId = sessionRepo.insertActive(clinicId, "TELEGRAM", patientUserId, "ru")
        adminToken = AdminFixtures.tokenFor(
            AdminFixtures.tokenService(),
            AdminFixtures.seedUser(userRepo, clinicId, "a@x.io", role = "admin"),
        )
    }

    /** Insert a confirmed internal booking row directly (the admin route reads/cancels it). */
    private fun seedBooking(): Long =
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO bookings (clinic_id, session_id, source_id, external_doctor_id, " +
                    "doctor_identity_id, specialist_code, slot_at, slot_end, external_appointment_id, status) " +
                    "VALUES (?, ?, 'internal', 'bk-doc', ?, 'therapist', ?, ?, NULL, 'confirmed') RETURNING id",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.setLong(3, identityId)
                ps.setTimestamp(4, Timestamp.from(slotAt))
                ps.setTimestamp(5, Timestamp.from(slotEnd))
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }

    private fun statusOf(id: Long): String =
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT status FROM bookings WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }

    // ---------------- list ----------------

    @Test
    fun `AC-7 list returns bookings with doctor display name, slot and status`() = testApplication {
        seedBooking()
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/bookings") {
            header("Authorization", "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "list must return 200; got ${resp.bodyAsText()}")
        val body = resp.bodyAsText()
        assertTrue(body.contains("Доктор Иванова"), "list must carry the doctor display name; got $body")
        assertTrue(body.contains("confirmed"), "list must carry the booking status; got $body")
    }

    // ---------------- cancel ----------------

    @Test
    fun `AC-7 cancel still succeeds (200) when the patient notification fails`() = testApplication {
        val bookingId = seedBooking()
        // The messaging module is unreachable / errors — the cancel must not be undone.
        val failing = object : BookingCancelNotifier {
            override suspend fun bookingCancelled(
                clinicId: Long,
                platformUserId: String,
                language: String,
                doctorName: String,
                slotAt: Instant,
            ) = throw RuntimeException("messaging module down")
        }
        installAdmin(AdminTestApp.deps(notifier = failing))
        val client = jsonClient()

        val resp = client.patch("/api/v1/admin/bookings/$bookingId") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"action":"cancel","reason":"x"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status,
            "a notification failure must NOT fail the cancel (state already committed); got ${resp.bodyAsText()}")
        assertEquals("cancelled", statusOf(bookingId), "the booking must still be cancelled in the DB")
        assertTrue(resp.bodyAsText().contains("\"notified\":false"),
            "the response must report the patient was not reached; got ${resp.bodyAsText()}")
    }

    @Test
    fun `AC-7 cancel sets status cancelled and pushes a booking-cancelled notification`() = testApplication {
        val bookingId = seedBooking()
        val notifier = RecordingCancelNotifier()
        installAdmin(AdminTestApp.deps(notifier = notifier))
        val client = jsonClient()

        val resp = client.patch("/api/v1/admin/bookings/$bookingId") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"action":"cancel","reason":"doctor sick"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "cancel must return 200; got ${resp.bodyAsText()}")
        assertEquals("cancelled", statusOf(bookingId), "the booking must be flipped to cancelled")
        assertTrue(resp.bodyAsText().contains("\"notified\":true"), "a delivered notification reports notified=true")

        assertEquals(1, notifier.sent.size, "cancel must push exactly one booking-cancelled notification")
        val sent = notifier.sent.single()
        assertEquals(clinicId, sent.clinicId)
        assertEquals(patientUserId, sent.platformUserId, "notification must target the booking's patient")
        assertEquals(slotAt, sent.slotAt)
    }

    @Test
    fun `AC-7 cancel frees the slot so the same slot can be booked again`() = testApplication {
        val bookingId = seedBooking()
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        client.patch("/api/v1/admin/bookings/$bookingId") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"action":"cancel","reason":"x"}""")
        }
        // The EXCLUDE guard only fires on confirmed rows; once cancelled, an
        // identical confirmed booking on the same doctor+slot must insert cleanly.
        val reinsert = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO bookings (clinic_id, session_id, source_id, external_doctor_id, " +
                    "doctor_identity_id, specialist_code, slot_at, slot_end, external_appointment_id, status) " +
                    "VALUES (?, ?, 'internal', 'bk-doc', ?, 'therapist', ?, ?, NULL, 'confirmed') RETURNING id",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.setLong(3, identityId)
                ps.setTimestamp(4, Timestamp.from(slotAt))
                ps.setTimestamp(5, Timestamp.from(slotEnd))
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        assertTrue(reinsert > 0, "cancelling must free the slot for a fresh confirmed booking")
    }

    @Test
    fun `AC-7 cancelling an already-cancelled booking returns 409`() = testApplication {
        val bookingId = seedBooking()
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        suspend fun cancel() = client.patch("/api/v1/admin/bookings/$bookingId") {
            header("Authorization", "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody("""{"action":"cancel","reason":"x"}""")
        }
        // First cancel succeeds; the second must conflict.
        val first = cancel()
        assertEquals(HttpStatusCode.OK, first.status)
        val second = cancel()
        assertEquals(HttpStatusCode.Conflict, second.status,
            "re-cancelling an already-cancelled booking must be 409")
    }
}
