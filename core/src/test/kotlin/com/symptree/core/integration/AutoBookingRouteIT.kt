package com.symptree.core.integration

import com.symptree.core.Fixtures
import com.symptree.core.booking.BookingService
import com.symptree.core.crypto.PiiCipher
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.BookingRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.db.PendingBookingRepo
import com.symptree.core.db.ReminderRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.llm.FakeLlmRouterClient
import com.symptree.core.llm.IntentClassification
import com.symptree.core.llm.Intent
import com.symptree.core.llm.OptionMapping
import com.symptree.core.model.NewDoctor
import com.symptree.core.plugins.configureSerialization
import com.symptree.core.plugins.configureStatusPages
import com.symptree.core.routes.botIngressRoutes
import com.symptree.core.source.DoctorRef
import com.symptree.core.source.SourceId
import com.symptree.core.symptomizer.Algorithm
import com.symptree.core.symptomizer.AlgorithmJson
import com.symptree.core.symptomizer.SessionService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-W03362.5 / .6 / .7 / .12 — drive the REAL bot path: symptomizer reaches a
 * BOOK_APPOINTMENT conclusion → the route renders `bot.booking.confirm_slot` with
 * yes/no buttons → `booking:confirm` → a `bookings` row + `bot.booking.confirmed`
 * + two reminders + the session concluded; a triple-decline → `bot.booking.contact_clinic`.
 *
 * This is the integration the service-level AutoBookingFlowIT does NOT cover (it
 * bypasses the symptomizer + routes).
 */
class AutoBookingRouteIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var algorithmRepo: AlgorithmRepo
    private lateinit var doctorRepo: DoctorRepo
    private lateinit var sessionRepo: SessionRepo
    private lateinit var bookingRepo: BookingRepo
    private lateinit var reminderRepo: ReminderRepo
    private lateinit var pendingRepo: PendingBookingRepo
    private var clinicId: Long = 0L

    // Monday 06:00Z so the Tuesday 10:00Z slot is >24h out (pre_visit_24h, not same_day).
    private val now = Instant.parse("2026-07-06T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val secret = "bot_test"

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        algorithmRepo = AlgorithmRepo(db)
        doctorRepo = DoctorRepo(db)
        sessionRepo = SessionRepo(db)
        bookingRepo = BookingRepo(db)
        reminderRepo = ReminderRepo(db)
        pendingRepo = PendingBookingRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "abr", defaultLanguage = "ru").clinic.id
        val algoId = algorithmRepo.insert(bookAlgorithm(), ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinicId, algoId, enabled = true)
    }

    private fun bookAlgorithm(): Algorithm = AlgorithmJson.instance.decodeFromString(
        Algorithm.serializer(),
        """
        {
          "algorithm_id": "book_test", "version": "1.0.0",
          "metadata": {"name": {"ru": "Запись", "kk": "Жазылу"}, "category": "general",
                       "age_group": "adult", "estimated_time_minutes": 1},
          "entry_point": "q1",
          "nodes": {
            "q1": {"type": "question", "text": {"ru": "Записать вас к терапевту?", "kk": "Терапевтке жазайын ба?"},
                   "options": [{"value": "yes", "label": {"ru": "Да", "kk": "Иә"}, "next": "book"}]},
            "book": {"type": "conclusion", "urgency": "routine",
                     "diagnosis": {"ru": "Нужна консультация терапевта", "kk": "Терапевт кеңесі қажет"},
                     "action": {"ru": "Запишитесь к терапевту", "kk": "Терапевтке жазылыңыз"},
                     "specialist": "therapist", "tests": [], "additional_tests": [], "preparation": {},
                     "clinic_action": "book_appointment", "follow_up_days": null}
          }
        }
        """.trimIndent(),
    )

    private fun seedDoctor(externalId: String, name: String) {
        val doc = doctorRepo.insert(
            NewDoctor(clinicId, "internal", externalId, name, listOf("therapist"), 30, true),
        )
        PostgresTestSupport.database().getConnection().use { conn: Connection ->
            conn.prepareStatement(
                "INSERT INTO doctor_weekly_hours (doctor_id, day_of_week, start_time, end_time) " +
                    "VALUES (?, 2, '10:00'::time, '18:00'::time)",
            ).use { ps -> ps.setLong(1, doc.id); ps.executeUpdate() }
        }
    }

    private fun bookingServiceFactory(): (Long) -> BookingService = { cid ->
        val scheduler = com.symptree.core.source.internal.InternalScheduler(doctorRepo, bookingRepo, "UTC", clock)
        BookingService(
            scheduling = scheduler,
            booking = scheduler,
            doctors = doctorRepo.listActive(cid).map {
                DoctorRef(SourceId(it.sourceId), it.externalId, it.identityId, it.fullName, it.specialistCodes)
            },
            reminders = ReminderRepo.sink(reminderRepo, cid),
            clock = clock,
            maxDeclines = 3,
        )
    }

    private fun ApplicationTestBuilder.installApp() {
        val service = SessionService(
            clinicRepo = clinicRepo,
            algorithmRepo = algorithmRepo,
            sessionRepo = sessionRepo,
            llm = FakeLlmRouterClient(
                intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
                mappingResponse = OptionMapping(pickedOptionValue = "yes", confidence = 0.9, clarifyingQuestion = null),
            ),
            piiCipher = PiiCipher(ByteArray(32) { it.toByte() }),
            bookingServiceFactory = bookingServiceFactory(),
            pendingBookingRepo = pendingRepo,
        )
        application {
            configureSerialization()
            configureStatusPages()
            routing { botIngressRoutes(clinicRepo, service, secret) }
        }
    }

    private suspend fun ApplicationTestBuilder.send(userId: String, text: String? = null, cb: String? = null): String {
        val body = buildString {
            append("""{"user":{"platform":"TELEGRAM","id":"""")
            append(userId).append("""","languageCode":"ru"}""")
            if (text != null) append(""","text":"""").append(text).append('"')
            if (cb != null) append(""","callbackData":"""").append(cb).append('"')
            append('}')
        }
        val resp = client.post("/api/v1/clinics/abr/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", secret)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.bodyAsText()
    }

    @Test
    fun `symptom to BOOK_APPOINTMENT proposes a slot, confirm books it with two reminders`() = testApplication {
        seedDoctor("doc-a", "Доктор Иванова")
        seedDoctor("doc-b", "Доктор Петрова")
        installApp()

        send("tg-bk", text = "запишите меня") // pre-pick → q1
        val proposal = send("tg-bk", text = "да") // walk → book conclusion → ConfirmSlot
        // FR-VWRNDR — the wire carries the semantic ConfirmSlot outcome (typed
        // fields), not chrome buttons; the frontend renders confirm/decline.
        assertTrue(proposal.contains("ConfirmSlot"), "auto-assign must emit a ConfirmSlot outcome; got $proposal")
        assertTrue(proposal.contains("doctorName"), "ConfirmSlot must carry the concrete doctor name; got $proposal")

        // The session is still active (the pending row IS the awaiting-confirm state),
        // and it holds the earliest slot (Tue 2026-07-07 10:00Z).
        val sessionId = sessionRepo.findActiveSession(clinicId, "TELEGRAM", "tg-bk")!!.id
        val pending = pendingRepo.find(clinicId, sessionId)!!
        assertEquals(Instant.parse("2026-07-07T10:00:00Z"), pending.slotAt, "earliest slot proposed")

        send("tg-bk", cb = "booking:confirm")

        // booking row: internal, no external appt id; two reminders; session concluded; pending cleared.
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT source_id, external_appointment_id, status FROM bookings WHERE session_id=?")
                .use { ps ->
                    ps.setLong(1, sessionId)
                    ps.executeQuery().use { rs ->
                        assertTrue(rs.next(), "a booking row must exist")
                        assertEquals("internal", rs.getString("source_id"))
                        assertNull(rs.getString("external_appointment_id"))
                        assertEquals("confirmed", rs.getString("status"))
                    }
                }
        }
        val reminderKinds = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT kind FROM reminders WHERE session_id=?").use { ps ->
                ps.setLong(1, sessionId)
                ps.executeQuery().use { rs -> val o = mutableListOf<String>(); while (rs.next()) o += rs.getString(1); o }
            }
        }
        assertEquals(setOf("pre_visit_24h", "post_visit_feedback"), reminderKinds.toSet())
        assertNull(pendingRepo.find(clinicId, sessionId), "pending row cleared after confirm")
        assertNull(sessionRepo.findActiveSession(clinicId, "TELEGRAM", "tg-bk"), "session concluded after confirm")
    }

    @Test
    fun `three declines fall back to contact_clinic and conclude`() = testApplication {
        seedDoctor("doc-a", "Доктор Иванова")
        seedDoctor("doc-b", "Доктор Петрова")
        installApp()

        send("tg-dk", text = "запишите меня")
        send("tg-dk", text = "да") // ConfirmSlot
        send("tg-dk", cb = "booking:decline") // decline 1 → re-propose
        send("tg-dk", cb = "booking:decline") // decline 2 → re-propose
        send("tg-dk", cb = "booking:decline") // decline 3 → contact_clinic, conclude

        // Outcome (catalog-independent): no booking, pending cleared, session concluded.
        val sessionId = sessionRepo.findLatestSession(clinicId, "TELEGRAM", "tg-dk")!!.id
        assertNull(pendingRepo.find(clinicId, sessionId), "pending cleared after contact-clinic")
        assertEquals(0, countBookings(), "no booking is created on the decline path")
        assertNull(sessionRepo.findActiveSession(clinicId, "TELEGRAM", "tg-dk"), "session concluded after 3 declines")
    }

    private fun countBookings(): Int =
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM bookings").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
}
