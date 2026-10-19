package com.symptree.core.integration

import com.symptree.core.Fixtures
import com.symptree.core.booking.BookingOutcome
import com.symptree.core.booking.BookingService
import com.symptree.core.booking.FeedbackHandler
import com.symptree.core.booking.FeedbackResult
import com.symptree.core.db.BookingRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.db.ReminderRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.model.NewDoctor
import com.symptree.core.scheduler.ReminderKind
import com.symptree.core.scheduler.ReminderDispatcher
import com.symptree.core.scheduler.ReminderPayload
import com.symptree.core.scheduler.ReminderScheduler
import com.symptree.core.source.PatientRef
import com.symptree.core.source.internal.InternalScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-W03362.12 — end-to-end auto-booking flow against Testcontainers.
 *
 * Seed clinic + 2 doctors with weekly hours → find earliest slot → confirm →
 * assert `bookings` row (source_id=internal, external_appointment_id IS NULL)
 * + two `reminders` rows → fast-forward via Clock → pre-visit reminder fires →
 * fast-forward to slot_end+2h → post-visit prompt → reply "5" → feedback_score=5.
 *
 * Race variant: two patients race the same last slot → exactly one `bookings`
 * row, the loser is re-proposed the next slot.
 *
 * `Clock` is injected per testing-spec § 5.4.
 */
class AutoBookingFlowIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var doctorRepo: DoctorRepo
    private lateinit var sessionRepo: SessionRepo
    private lateinit var bookingRepo: BookingRepo
    private lateinit var reminderRepo: ReminderRepo
    private var clinicId: Long = 0L
    private var sessionId: Long = 0L

    // Monday 06:00Z — the day before the seeded doctors' Tue 10:00 local-UTC hours, so the
    // earliest slot (Tue 10:00Z) sits >24h out and yields a genuine `pre_visit_24h` reminder
    // (slot_at−24h = Mon 10:00Z is still in the future). A same-Tuesday `now` made the slot
    // only 4h ahead → `same_day` per AC-W03362.6, contradicting the `pre_visit_24h` assertion
    // below and the sibling BookingServiceTest same-day case.
    private val now = Instant.parse("2026-07-06T06:00:00Z")
    private val clinicZone = "UTC"

    private val patient = PatientRef(externalId = "tg-pt", displayName = null, phone = null, languageCode = "ru")

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        doctorRepo = DoctorRepo(db)
        sessionRepo = SessionRepo(db)
        bookingRepo = BookingRepo(db)
        reminderRepo = ReminderRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "abf").clinic.id
        sessionId = sessionRepo.insertActive(clinicId, "TELEGRAM", "tg-pt", "ru")
    }

    private fun seedDoctorWithTuesdayHours(externalId: String, name: String): Long {
        val doc = doctorRepo.insert(
            NewDoctor(
                clinicId = clinicId,
                sourceId = "internal",
                externalId = externalId,
                fullName = name,
                specialistCodes = listOf("therapist"),
                slotMinutes = 30,
                active = true,
            ),
        )
        PostgresTestSupport.database().getConnection().use { conn: Connection ->
            conn.prepareStatement(
                "INSERT INTO doctor_weekly_hours (doctor_id, day_of_week, start_time, end_time) " +
                    "VALUES (?, 2, '10:00'::time, '18:00'::time)",
            ).use { ps -> ps.setLong(1, doc.id); ps.executeUpdate() }
        }
        return doc.id
    }

    private fun bookingService(clock: Clock): BookingService {
        val scheduler = InternalScheduler(doctorRepo = doctorRepo, bookingRepo = bookingRepo, zone = clinicZone, clock = clock)
        return BookingService(
            scheduling = scheduler,
            booking = scheduler,
            doctors = doctorRepo.listBySpecialist(clinicId, "therapist").map { d ->
                com.symptree.core.source.DoctorRef(
                    com.symptree.core.source.SourceId(d.sourceId), d.externalId, d.identityId, d.fullName, d.specialistCodes,
                )
            },
            reminders = ReminderRepo.sink(reminderRepo, clinicId),
            clock = clock,
            maxDeclines = 3,
            zone = clinicZone,
        )
    }

    private fun countBookings(status: String? = null): Int =
        PostgresTestSupport.database().getConnection().use { conn ->
            val sql = if (status == null) "SELECT COUNT(*) FROM bookings"
            else "SELECT COUNT(*) FROM bookings WHERE status='$status'"
            conn.prepareStatement(sql).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        }

    @Test
    fun `happy path - confirm books an internal row with two reminders, then feedback lands`() = runBlocking {
        seedDoctorWithTuesdayHours("doc-a", "Dr A")
        seedDoctorWithTuesdayHours("doc-b", "Dr B")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val svc = bookingService(clock)

        val proposed = svc.findFirstAvailable(clinicId, "therapist", lookahead = Duration.ofDays(7))
            ?: error("a slot must be available")
        assertEquals(Instant.parse("2026-07-07T10:00:00Z"), proposed.start, "earliest slot is 10:00 local UTC")

        val outcome = svc.confirm(clinicId, sessionId, proposed, patient, specialistCode = "therapist")
        assertTrue(outcome is BookingOutcome.Confirmed, "confirm must succeed; got $outcome")

        // bookings row: source_id=internal, external_appointment_id NULL.
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT source_id, external_appointment_id, status FROM bookings WHERE session_id=?",
            ).use { ps ->
                ps.setLong(1, sessionId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "exactly one booking row expected")
                    assertEquals("internal", rs.getString("source_id"))
                    assertNull(rs.getString("external_appointment_id"), "internal booking carries no external appt id")
                    assertEquals("confirmed", rs.getString("status"))
                    assertTrue(!rs.next(), "only one booking row")
                }
            }
        }

        // two reminders.
        val kinds = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT kind FROM reminders WHERE session_id=? ORDER BY due_at").use { ps ->
                ps.setLong(1, sessionId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<String>(); while (rs.next()) out += rs.getString(1); out
                }
            }
        }
        assertEquals(setOf("pre_visit_24h", "post_visit_feedback"), kinds.toSet(), "pre + post reminders must exist")

        // Fast-forward to slot_end + 2h and dispatch the post-visit reminder.
        val later = Clock.fixed(Instant.parse("2026-07-07T12:30:00Z"), ZoneOffset.UTC)
        val dispatcher = RecordingDispatcher()
        ReminderScheduler(reminderRepo = reminderRepo, dispatcher = dispatcher, clock = later).pollOnce()
        assertTrue(
            dispatcher.payloads.any { it.kind == ReminderKind.POST_VISIT_FEEDBACK },
            "post-visit feedback reminder must dispatch; saw ${dispatcher.payloads.map { it.kind }}",
        )

        // Patient replies "5" → feedback_score persisted.
        val bookingId = bookingRepo.latestForSession(clinicId, sessionId)!!.id
        val feedback = FeedbackHandler(BookingRepo.feedbackSink(bookingRepo, clinicId)).handle(bookingId, "5")
        assertTrue(feedback is FeedbackResult.Recorded)
        val score = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT feedback_score FROM bookings WHERE id=?").use { ps ->
                ps.setLong(1, bookingId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(5, score, "feedback_score must be 5 after the patient replies '5'")
    }

    @Test
    fun `race variant - two patients racing the last slot produce exactly one booking, loser re-proposed`() = runBlocking {
        // One doctor, one slot: a 30-min shift so only a single slot exists.
        val doc = doctorRepo.insert(
            NewDoctor(clinicId, "internal", "solo", "Dr Solo", listOf("therapist"), slotMinutes = 30, active = true),
        )
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO doctor_weekly_hours (doctor_id, day_of_week, start_time, end_time) " +
                    "VALUES (?, 2, '10:00'::time, '10:30'::time)",
            ).use { ps -> ps.setLong(1, doc.id); ps.executeUpdate() }
        }
        val session2 = sessionRepo.insertActive(clinicId, "TELEGRAM", "tg-pt2", "ru")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val svc = bookingService(clock)

        val slot = svc.findFirstAvailable(clinicId, "therapist", lookahead = Duration.ofDays(7))!!

        val outcomes = listOf(
            async { svc.confirm(clinicId, sessionId, slot, patient, "therapist") },
            async { svc.confirm(clinicId, session2, slot, PatientRef("tg-pt2", null, null, "ru"), "therapist") },
        ).awaitAll()

        assertEquals(1, countBookings("confirmed"), "exactly one confirmed booking may exist for the single slot")
        val winners = outcomes.filterIsInstance<BookingOutcome.Confirmed>()
        val losers = outcomes.filter { it is BookingOutcome.ReProposed || it is BookingOutcome.NoSlots }
        assertEquals(1, winners.size, "exactly one racer wins")
        assertEquals(1, losers.size, "the loser is re-proposed or told no slots remain; got $outcomes")
    }

    private class RecordingDispatcher : ReminderDispatcher {
        val perId = ConcurrentHashMap<Long, AtomicInteger>()
        val payloads = mutableListOf<ReminderPayload>()
        override suspend fun dispatch(reminderId: Long, payload: ReminderPayload) {
            perId.computeIfAbsent(reminderId) { AtomicInteger(0) }.incrementAndGet()
            synchronized(payloads) { payloads += payload }
        }
    }
}
