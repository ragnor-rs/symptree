package com.symptree.core.booking

import com.symptree.core.scheduler.ReminderKind
import com.symptree.core.source.BookingProvider
import com.symptree.core.source.BookingResult
import com.symptree.core.source.DoctorRef
import com.symptree.core.source.ExternalApptRef
import com.symptree.core.source.PatientRef
import com.symptree.core.source.SchedulingProvider
import com.symptree.core.source.Slot
import com.symptree.core.source.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-W03362.5/.6/.7 — `BookingService` confirm/decline/no-slot paths and the
 * `SlotTakenException` re-propose loop.
 *
 * Uses in-memory fakes per testing-spec.md § 5.6. `Clock` is injected per § 5.4.
 */
class BookingServiceTest {

    private val now = Instant.parse("2026-07-01T08:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val docA = DoctorRef(SourceId("internal"), "doc-a", 1L, "Dr A", listOf("therapist"))

    private fun slot(doc: DoctorRef, start: String) =
        Slot(doc, Instant.parse(start), Instant.parse(start).plusSeconds(1800))

    /** In-memory scheduling/booking provider with a fixed slot calendar. */
    private class FakeInternalProvider(
        var calendar: MutableList<Slot>,
        var bookThrows: Throwable? = null,
    ) : SchedulingProvider, BookingProvider {
        val booked = mutableListOf<Slot>()
        var bookCallCount = 0
        override suspend fun availability(doctor: DoctorRef, window: ClosedRange<Instant>): List<Slot> =
            calendar.filter { it.doctorRef.externalId == doctor.externalId && it.start in window }
        override suspend fun book(slot: Slot, patient: PatientRef): BookingResult {
            bookCallCount += 1
            bookThrows?.let { t -> bookThrows = null; throw t }
            booked += slot
            calendar.removeIf { it.start == slot.start && it.doctorRef.externalId == slot.doctorRef.externalId }
            return BookingResult(externalRef = null, confirmedSlot = slot)
        }
        override suspend fun cancel(ref: ExternalApptRef) = Unit
    }

    /** Records the semantic reminders inserted by the service (FR-VWRNDR). */
    private class RecordingReminderSink : ReminderSink {
        data class Rem(
            val kind: ReminderKind,
            val dueAt: Instant,
            val doctorName: String,
            val slotAt: Instant,
            val clinicZone: String,
        )
        val reminders = mutableListOf<Rem>()
        override fun insert(
            sessionId: Long,
            clinicId: Long,
            kind: ReminderKind,
            dueAt: Instant,
            doctorName: String,
            slotAt: Instant,
            clinicZone: String,
        ) {
            reminders += Rem(kind, dueAt, doctorName, slotAt, clinicZone)
        }
    }

    private val patient = PatientRef(externalId = "tg-1", displayName = null, phone = null, languageCode = "ru")

    private fun service(
        provider: FakeInternalProvider,
        reminders: ReminderSink,
        maxDeclines: Int = 3,
        zone: String = "UTC",
        minLead: Duration = Duration.ZERO,
        clock: Clock = this.clock,
    ): BookingService =
        BookingService(
            scheduling = provider,
            booking = provider,
            doctors = listOf(docA),
            reminders = reminders,
            clock = clock,
            maxDeclines = maxDeclines,
            minLead = minLead,
            zone = zone,
        )

    // ---------------- AC.5: findFirstAvailable ----------------

    @Test
    fun `findFirstAvailable returns the earliest slot for a matching specialist`() = runBlocking {
        val provider = FakeInternalProvider(
            mutableListOf(slot(docA, "2026-07-02T09:30:00Z"), slot(docA, "2026-07-02T09:00:00Z")),
        )
        val svc = service(provider, RecordingReminderSink())

        val proposed = svc.findFirstAvailable(clinicId = 7L, specialistCode = "therapist", lookahead = Duration.ofDays(7))

        assertNotNull(proposed, "a slot exists → must be proposed")
        assertEquals(Instant.parse("2026-07-02T09:00:00Z"), proposed.start, "earliest matching slot wins")
    }

    @Test
    fun `findFirstAvailable returns null when no slot exists in the lookahead window`() = runBlocking {
        val provider = FakeInternalProvider(mutableListOf())
        val svc = service(provider, RecordingReminderSink())

        val proposed = svc.findFirstAvailable(clinicId = 7L, specialistCode = "therapist", lookahead = Duration.ofDays(7))

        assertNull(proposed, "empty calendar → no slot")
    }

    // ---------------- AC.13: minimum booking lead time ----------------

    @Test
    fun `findFirstAvailable never proposes a slot inside the minimum lead window`() = runBlocking {
        // now=08:00Z, lead=24h → nothing before 2026-07-02T08:00Z is eligible.
        val near = slot(docA, "2026-07-01T10:00:00Z") // +2h, inside the lead window
        val far = slot(docA, "2026-07-02T09:00:00Z") // +25h, eligible
        val provider = FakeInternalProvider(mutableListOf(near, far))
        val svc = service(provider, RecordingReminderSink(), minLead = Duration.ofHours(24))

        val proposed = svc.findFirstAvailable(clinicId = 7L, specialistCode = "therapist", lookahead = Duration.ofDays(30))

        assertNotNull(proposed, "an eligible slot beyond the lead window exists")
        assertEquals(
            Instant.parse("2026-07-02T09:00:00Z"), proposed.start,
            "the near slot inside the 24h lead window must be skipped",
        )
    }

    @Test
    fun `decline re-propose also respects the minimum lead window`() = runBlocking {
        val near = slot(docA, "2026-07-01T10:00:00Z") // inside the lead window
        val far = slot(docA, "2026-07-02T09:00:00Z") // eligible
        val declined = slot(docA, "2026-07-03T09:00:00Z")
        val provider = FakeInternalProvider(mutableListOf(near, far))
        val svc = service(provider, RecordingReminderSink(), minLead = Duration.ofHours(24))

        val result = svc.decline(
            clinicId = 7L, sessionId = 99L, declinedStarts = setOf(declined.start),
            specialistCode = "therapist", declineCount = 1,
        )

        assertTrue(result is BookingOutcome.ReProposed, "an eligible slot remains → re-propose, got $result")
        assertEquals(
            far.start, (result as BookingOutcome.ReProposed).slot.start,
            "the near slot inside the lead window must not be re-proposed",
        )
    }

    @Test
    fun `decline excludes ALL previously-declined slots, not just the latest`() = runBlocking {
        // The bug: declining 09:00 → re-propose 09:30; declining 09:30 → re-propose
        // 09:00 AGAIN (only the latest was excluded). With the full declined set
        // excluded, the second decline has no slot left → contact-clinic, never a
        // cycle back to an already-rejected time.
        val s9 = slot(docA, "2026-07-02T09:00:00Z")
        val s930 = slot(docA, "2026-07-02T09:30:00Z")
        val provider = FakeInternalProvider(mutableListOf(s9, s930))
        val svc = service(provider, RecordingReminderSink())

        val first = svc.decline(
            clinicId = 7L, sessionId = 99L, declinedStarts = setOf(s9.start),
            specialistCode = "therapist", declineCount = 1,
        )
        assertEquals(
            s930.start, (first as BookingOutcome.ReProposed).slot.start,
            "after declining 09:00 the next-earliest 09:30 is proposed",
        )

        val second = svc.decline(
            clinicId = 7L, sessionId = 99L, declinedStarts = setOf(s9.start, s930.start),
            specialistCode = "therapist", declineCount = 2,
        )
        assertTrue(
            second is BookingOutcome.ContactClinic,
            "both slots declined → contact-clinic, NOT a cycle back to 09:00; got $second",
        )
    }

    // ---------------- AC.6: confirm ----------------

    @Test
    fun `confirm books the slot and inserts pre-visit and post-visit reminders`() = runBlocking {
        val target = slot(docA, "2026-07-10T09:00:00Z")
        val provider = FakeInternalProvider(mutableListOf(target))
        val reminders = RecordingReminderSink()
        val svc = service(provider, reminders)

        val result = svc.confirm(clinicId = 7L, sessionId = 99L, slot = target, patient = patient, specialistCode = "therapist")

        assertTrue(result is BookingOutcome.Confirmed, "confirm with a free slot → Confirmed, got $result")
        assertEquals(listOf(target), provider.booked, "the slot must be booked exactly once")
        // pre-visit 24h before slot_at, post-visit 2h after slot_end.
        val pre = reminders.reminders.single { it.kind == ReminderKind.PRE_VISIT_24H }
        assertEquals(Instant.parse("2026-07-09T09:00:00Z"), pre.dueAt, "pre-visit due at slot_at - 24h")
        val post = reminders.reminders.single { it.kind == ReminderKind.POST_VISIT_FEEDBACK }
        assertEquals(Instant.parse("2026-07-10T11:30:00Z"), post.dueAt, "post-visit due at slot_end + 2h")
    }

    @Test
    fun `confirm emits semantic reminder fields (raw slot + zone) for the frontend to format`() = runBlocking {
        // FR-VWRNDR/AC-VWRNDR.7 — core no longer formats time/date; it ships the
        // raw slot Instant + clinic zone and the messaging frontend formats them.
        val target = slot(docA, "2026-07-10T09:00:00Z")
        val provider = FakeInternalProvider(mutableListOf(target))
        val reminders = RecordingReminderSink()
        val svc = service(provider, reminders, zone = "Asia/Almaty")

        svc.confirm(clinicId = 7L, sessionId = 99L, slot = target, patient = patient, specialistCode = "therapist")

        val pre = reminders.reminders.single { it.kind == ReminderKind.PRE_VISIT_24H }
        assertEquals("Dr A", pre.doctorName, "doctor name carried as concrete data")
        assertEquals(target.start, pre.slotAt, "raw slot Instant carried — no in-core formatting")
        assertEquals("Asia/Almaty", pre.clinicZone, "clinic zone carried for frontend formatting")
    }

    @Test
    fun `confirm same-day uses a now-plus-1-min same_day reminder when slot_at minus 24h is in the past`() = runBlocking {
        // clock=2026-07-01T08:00Z; slot 12:00 today → slot_at-24h is in the past.
        val target = slot(docA, "2026-07-01T12:00:00Z")
        val provider = FakeInternalProvider(mutableListOf(target))
        val reminders = RecordingReminderSink()
        val svc = service(provider, reminders)

        svc.confirm(clinicId = 7L, sessionId = 99L, slot = target, patient = patient, specialistCode = "therapist")

        val sameDay = reminders.reminders.single { it.kind == ReminderKind.SAME_DAY }
        assertEquals(now.plusSeconds(60), sameDay.dueAt, "same-day reminder due now()+1min")
    }

    @Test
    fun `confirm next-day slot under 24h out is a pre-visit reminder, not same-day`() = runBlocking {
        // clock=2026-07-01T08:00Z; slot tomorrow 07:00 (+23h). slot_at-24h is in the
        // past, but the visit is the NEXT clinic-local calendar day, so the reminder
        // must be the "завтра/ертең" pre-visit — NOT the "сегодня/бүгін" same-day one
        // (the bilingual wording bug the M2 smoke surfaced). When slot_at-24h is past,
        // the pre-visit fires promptly (now+1min) rather than at a past instant.
        val target = slot(docA, "2026-07-02T07:00:00Z")
        val provider = FakeInternalProvider(mutableListOf(target))
        val reminders = RecordingReminderSink()
        val svc = service(provider, reminders) // zone=UTC

        svc.confirm(clinicId = 7L, sessionId = 99L, slot = target, patient = patient, specialistCode = "therapist")

        assertTrue(
            reminders.reminders.none { it.kind == ReminderKind.SAME_DAY },
            "a next-day visit must NOT use the same-day reminder",
        )
        val pre = reminders.reminders.single { it.kind == ReminderKind.PRE_VISIT_24H }
        assertEquals(
            now.plusSeconds(60), pre.dueAt,
            "slot_at-24h is already past → the pre-visit fires now()+1min, never a past instant",
        )
    }

    @Test
    fun `same-day classification uses the clinic-local date, not the UTC date`() = runBlocking {
        // now=2026-07-01T20:00Z = 2026-07-02T01:00 in Asia/Almaty (+05). A slot at
        // 2026-07-02T04:00Z = 09:00 Almaty is the SAME clinic-local day (07-02) as
        // "now" locally, so it must be a same-day reminder — even though the UTC
        // dates differ (07-01 vs 07-02). Proves we key off the clinic zone.
        val nowAlmaty = Instant.parse("2026-07-01T20:00:00Z")
        val target = slot(docA, "2026-07-02T04:00:00Z")
        val provider = FakeInternalProvider(mutableListOf(target))
        val reminders = RecordingReminderSink()
        val svc = service(provider, reminders, zone = "Asia/Almaty", clock = Clock.fixed(nowAlmaty, ZoneOffset.UTC))

        svc.confirm(clinicId = 7L, sessionId = 99L, slot = target, patient = patient, specialistCode = "therapist")

        val sameDay = reminders.reminders.single { it.kind == ReminderKind.SAME_DAY }
        assertEquals(nowAlmaty.plusSeconds(60), sameDay.dueAt, "same clinic-local day → same-day reminder")
    }

    @Test
    fun `confirm on SlotTaken re-runs findFirstAvailable and re-proposes the next slot`() = runBlocking {
        val taken = slot(docA, "2026-07-10T09:00:00Z")
        val next = slot(docA, "2026-07-10T09:30:00Z")
        val provider = FakeInternalProvider(
            calendar = mutableListOf(next), // `taken` already gone from the calendar
            bookThrows = SlotTakenException("doc-a", taken.start, taken.end),
        )
        val svc = service(provider, RecordingReminderSink())

        val result = svc.confirm(clinicId = 7L, sessionId = 99L, slot = taken, patient = patient, specialistCode = "therapist")

        assertTrue(
            result is BookingOutcome.ReProposed,
            "a SlotTakenException must re-propose the next-earliest slot, got $result",
        )
        assertEquals(next.start, (result as BookingOutcome.ReProposed).slot.start, "re-proposed the next free slot")
    }

    // ---------------- AC.7: decline ----------------

    @Test
    fun `decline re-proposes the next-earliest slot excluding the declined one`() = runBlocking {
        val declined = slot(docA, "2026-07-10T09:00:00Z")
        val next = slot(docA, "2026-07-10T09:30:00Z")
        val provider = FakeInternalProvider(mutableListOf(declined, next))
        val svc = service(provider, RecordingReminderSink())

        val result = svc.decline(
            clinicId = 7L,
            sessionId = 99L,
            declinedStarts = setOf(declined.start),
            specialistCode = "therapist",
            declineCount = 1,
        )

        assertTrue(result is BookingOutcome.ReProposed, "decline → re-propose, got $result")
        assertEquals(next.start, (result as BookingOutcome.ReProposed).slot.start, "the declined slot is excluded")
    }

    @Test
    fun `decline after N declines falls back to contact_clinic and concludes`() = runBlocking {
        val declined = slot(docA, "2026-07-10T09:00:00Z")
        val next = slot(docA, "2026-07-10T09:30:00Z")
        val provider = FakeInternalProvider(mutableListOf(declined, next))
        val svc = service(provider, RecordingReminderSink(), maxDeclines = 3)

        val result = svc.decline(
            clinicId = 7L,
            sessionId = 99L,
            declinedStarts = setOf(declined.start),
            specialistCode = "therapist",
            declineCount = 3, // already at the limit
        )

        assertTrue(
            result is BookingOutcome.ContactClinic,
            "after maxDeclines the service must fall back to contact_clinic, got $result",
        )
    }
}
