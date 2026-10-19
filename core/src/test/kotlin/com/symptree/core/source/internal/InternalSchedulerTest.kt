package com.symptree.core.source.internal

import com.symptree.core.source.DoctorRef
import com.symptree.core.source.Slot
import com.symptree.core.source.SourceId
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-W03362.1 — `InternalScheduler.availability` is a PURE function of
 * (weeklyHours, timeOff, existingBookings, slotMinutes, window) → List<Slot>.
 *
 * No DB. DST-correct via the clinic IANA zone. `day_of_week` 0=Sun..6=Sat in
 * clinic-local time. Trailing remainder shorter than `slot_minutes` is dropped.
 *
 * See technical-spec.md § 3.4 *Scheduling time model*.
 */
class InternalSchedulerTest {

    private val doctor = DoctorRef(
        sourceId = SourceId("internal"),
        externalId = "doc-1",
        identityId = 1L,
        displayName = "Dr Test",
        specialistCodes = listOf("therapist"),
    )

    private fun wh(dow: Int, start: String, end: String) =
        WeeklyHours(dayOfWeek = dow, start = LocalTime.parse(start), end = LocalTime.parse(end))

    private fun timeOff(start: String, end: String) =
        TimeOff(start = Instant.parse(start), end = Instant.parse(end))

    private fun window(from: String, to: String): ClosedRange<Instant> =
        Instant.parse(from)..Instant.parse(to)

    // ---------------- basic grid subdivision ----------------

    @Test
    fun `tiles a single shift into slot_minutes grid slots, dropping trailing remainder`() {
        // Tue 2026-07-07 10:00-11:10 local UTC, 30-min slots => two slots, 10-min remainder dropped.
        val slots = InternalScheduler.availability(
            doctor = doctor,
            weeklyHours = listOf(wh(2, "10:00", "11:10")),
            timeOff = emptyList(),
            existingBookings = emptyList(),
            slotMinutes = 30,
            zone = "UTC",
            window = window("2026-07-07T00:00:00Z", "2026-07-08T00:00:00Z"),
        )
        assertEquals(
            listOf(
                Instant.parse("2026-07-07T10:00:00Z") to Instant.parse("2026-07-07T10:30:00Z"),
                Instant.parse("2026-07-07T10:30:00Z") to Instant.parse("2026-07-07T11:00:00Z"),
            ),
            slots.map { it.start to it.end },
            "two 30-min slots, the trailing 10-min remainder dropped",
        )
    }

    // ---------------- DST / IANA zone correctness ----------------

    @Test
    fun `resolves local wall-clock through the clinic IANA zone, not a fixed offset`() {
        // Asia/Tbilisi is UTC+4 (no DST). Tue 2026-07-07 10:00-18:00 local => 06:00Z..14:00Z.
        val slots = InternalScheduler.availability(
            doctor = doctor,
            weeklyHours = listOf(wh(2, "10:00", "18:00")),
            timeOff = emptyList(),
            existingBookings = emptyList(),
            slotMinutes = 30,
            zone = "Asia/Tbilisi",
            window = window("2026-07-07T00:00:00Z", "2026-07-08T00:00:00Z"),
        )
        assertEquals(16, slots.size, "8h tiled by 30min = 16 slots")
        assertEquals(Instant.parse("2026-07-07T06:00:00Z"), slots.first().start, "10:00 Tbilisi == 06:00Z")
        assertEquals(Instant.parse("2026-07-07T14:00:00Z"), slots.last().end, "18:00 Tbilisi == 14:00Z")
    }

    @Test
    fun `evaluates day_of_week in clinic-local time using 0=Sun`() {
        // 2026-07-07 is a Tuesday => day_of_week 2 (0=Sun..6=Sat). Sanity-pin the convention.
        assertEquals(DayOfWeek.TUESDAY, LocalDate.parse("2026-07-07").dayOfWeek)
        val onTuesday = InternalScheduler.availability(
            doctor = doctor,
            weeklyHours = listOf(wh(2, "09:00", "10:00")),
            timeOff = emptyList(),
            existingBookings = emptyList(),
            slotMinutes = 30,
            zone = "UTC",
            window = window("2026-07-07T00:00:00Z", "2026-07-08T00:00:00Z"),
        )
        assertEquals(2, onTuesday.size, "Tuesday hours (dow=2) must produce slots on a Tuesday")

        // A row with dow=3 (Wed) must produce NO slots inside a Tuesday-only window.
        val wedRowOnTuesday = InternalScheduler.availability(
            doctor = doctor,
            weeklyHours = listOf(wh(3, "09:00", "10:00")),
            timeOff = emptyList(),
            existingBookings = emptyList(),
            slotMinutes = 30,
            zone = "UTC",
            window = window("2026-07-07T00:00:00Z", "2026-07-08T00:00:00Z"),
        )
        assertTrue(wedRowOnTuesday.isEmpty(), "a Wednesday hours row must not match a Tuesday window")
    }

    // ---------------- split shifts ----------------

    @Test
    fun `split shifts on the same day produce two disjoint slot ranges`() {
        val slots = InternalScheduler.availability(
            doctor = doctor,
            weeklyHours = listOf(wh(2, "09:00", "10:00"), wh(2, "14:00", "15:00")),
            timeOff = emptyList(),
            existingBookings = emptyList(),
            slotMinutes = 30,
            zone = "UTC",
            window = window("2026-07-07T00:00:00Z", "2026-07-08T00:00:00Z"),
        )
        assertEquals(
            listOf(
                Instant.parse("2026-07-07T09:00:00Z"),
                Instant.parse("2026-07-07T09:30:00Z"),
                Instant.parse("2026-07-07T14:00:00Z"),
                Instant.parse("2026-07-07T14:30:00Z"),
            ),
            slots.map { it.start },
            "morning + afternoon split shift, no slots in the 10:00-14:00 gap",
        )
    }

    // ---------------- time-off subtraction ----------------

    @Test
    fun `partial-day time-off removes only the overlapping slots`() {
        // 09:00-12:00 minus time-off 10:00-11:00 => slots at 09:00, 09:30, 11:00, 11:30.
        val slots = InternalScheduler.availability(
            doctor = doctor,
            weeklyHours = listOf(wh(2, "09:00", "12:00")),
            timeOff = listOf(timeOff("2026-07-07T10:00:00Z", "2026-07-07T11:00:00Z")),
            existingBookings = emptyList(),
            slotMinutes = 30,
            zone = "UTC",
            window = window("2026-07-07T00:00:00Z", "2026-07-08T00:00:00Z"),
        )
        assertEquals(
            listOf(
                Instant.parse("2026-07-07T09:00:00Z"),
                Instant.parse("2026-07-07T09:30:00Z"),
                Instant.parse("2026-07-07T11:00:00Z"),
                Instant.parse("2026-07-07T11:30:00Z"),
            ),
            slots.map { it.start },
            "the 10:00-11:00 time-off window must be excised",
        )
    }

    // ---------------- existing-booking exclusion ----------------

    @Test
    fun `existing confirmed bookings remove the overlapping slot`() {
        val slots = InternalScheduler.availability(
            doctor = doctor,
            weeklyHours = listOf(wh(2, "09:00", "11:00")),
            timeOff = emptyList(),
            existingBookings = listOf(
                Slot(doctor, Instant.parse("2026-07-07T09:30:00Z"), Instant.parse("2026-07-07T10:00:00Z")),
            ),
            slotMinutes = 30,
            zone = "UTC",
            window = window("2026-07-07T00:00:00Z", "2026-07-08T00:00:00Z"),
        )
        assertEquals(
            listOf(
                Instant.parse("2026-07-07T09:00:00Z"),
                Instant.parse("2026-07-07T10:00:00Z"),
                Instant.parse("2026-07-07T10:30:00Z"),
            ),
            slots.map { it.start },
            "the 09:30 slot is taken by an existing booking and must be excluded",
        )
    }

    // ---------------- empty window ----------------

    @Test
    fun `empty window or no matching hours yields no slots`() {
        val none = InternalScheduler.availability(
            doctor = doctor,
            weeklyHours = listOf(wh(2, "09:00", "17:00")),
            timeOff = emptyList(),
            existingBookings = emptyList(),
            slotMinutes = 30,
            zone = "UTC",
            // window is a Wednesday (dow=3); Tuesday hours never fire.
            window = window("2026-07-08T00:00:00Z", "2026-07-09T00:00:00Z"),
        )
        assertTrue(none.isEmpty(), "no weekly-hours row matches the window's day → no slots")
    }
}
