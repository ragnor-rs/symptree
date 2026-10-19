package com.symptree.core.source.internal

import com.symptree.core.db.BookingRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.source.BookingProvider
import com.symptree.core.source.BookingResult
import com.symptree.core.source.DoctorRef
import com.symptree.core.source.ExternalApptRef
import com.symptree.core.source.PatientRef
import com.symptree.core.source.SchedulingProvider
import com.symptree.core.source.Slot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A single weekly-hours row: a local wall-clock `[start, end)` shift on a given
 * [dayOfWeek] (0=Sun..6=Sat), anchored by the clinic's IANA timezone.
 */
data class WeeklyHours(
    val dayOfWeek: Int,
    val start: LocalTime,
    val end: LocalTime,
)

/** A doctor time-off interval, expressed as an absolute UTC `[start, end)`. */
data class TimeOff(
    val start: Instant,
    val end: Instant,
)

/**
 * The INTERNAL [SchedulingProvider] + [BookingProvider] (FR-YDANPW), backed by the
 * internal `doctor_weekly_hours` / `doctor_time_off` / `bookings` tables.
 *
 * The heavy lifting — turning weekly hours minus time-off minus confirmed bookings
 * into a grid of bookable [Slot]s — lives in the **pure** [availability] companion
 * function (DB-free, DST-correct via the clinic IANA zone). See
 * technical-spec.md § 3.4 *Scheduling time model*.
 */
class InternalScheduler(
    private val doctorRepo: DoctorRepo,
    private val bookingRepo: BookingRepo,
    private val zone: String,
    // [clock] is part of the pinned constructor contract but currently unread: [availability]
    // receives an explicit window whose lower bound [BookingService] already pins to `now()`, so
    // this provider never needs its own clock. Retained for symmetry + future server-side clipping.
    @Suppress("UnusedPrivateProperty") private val clock: Clock,
) : SchedulingProvider, BookingProvider {

    override suspend fun availability(doctor: DoctorRef, window: ClosedRange<Instant>): List<Slot> {
        val persisted = doctorRepo.findByExternalKey(doctor.sourceId.value, doctor.externalId)
            ?: return emptyList()
        val existingBookings = bookingRepo
            .confirmedBookings(persisted.clinicId, doctor.externalId)
            .map { Slot(doctor, it.slotAt, it.slotEnd) }
        return availability(
            doctor = doctor,
            weeklyHours = doctorRepo.weeklyHours(persisted.id),
            timeOff = doctorRepo.timeOff(persisted.id),
            existingBookings = existingBookings,
            slotMinutes = persisted.slotMinutes,
            zone = zone,
            window = window,
        )
    }

    override suspend fun book(slot: Slot, patient: PatientRef): BookingResult {
        val persisted = doctorRepo.findByExternalKey(slot.doctorRef.sourceId.value, slot.doctorRef.externalId)
            ?: error("internal booking for unknown doctor ${slot.doctorRef.externalId}")
        bookingRepo.insert(persisted.clinicId, slot, patient)
        return BookingResult(externalRef = null, confirmedSlot = slot)
    }

    override suspend fun cancel(ref: ExternalApptRef) {
        // Internal cancellations are keyed by (doctor, slot), not by an external ref;
        // the internal source carries no external appointment id. No-op for the M2 flow.
    }

    companion object {

        /**
         * PURE availability: `weeklyHours − timeOff − existingBookings`, subdivided
         * into [slotMinutes] grid slots, clipped to [window], emitted as UTC [Slot]s.
         *
         * `weeklyHours` start/end are local wall-clock evaluated in [zone] (an IANA
         * zone, DST-correct); `dayOfWeek` is 0=Sun..6=Sat in clinic-local time. A
         * trailing remainder shorter than [slotMinutes] is dropped.
         */
        @Suppress("LongParameterList")
        fun availability(
            doctor: DoctorRef,
            weeklyHours: List<WeeklyHours>,
            timeOff: List<TimeOff>,
            existingBookings: List<Slot>,
            slotMinutes: Int,
            zone: String,
            window: ClosedRange<Instant>,
        ): List<Slot> {
            val zoneId = ZoneId.of(zone)
            val slotDuration = Duration.ofMinutes(slotMinutes.toLong())

            val firstDate = LocalDate.ofInstant(window.start, zoneId)
            val lastDate = LocalDate.ofInstant(window.endInclusive, zoneId)

            val slots = mutableListOf<Slot>()
            var date = firstDate
            while (!date.isAfter(lastDate)) {
                val dow = sundayZeroDayOfWeek(date)
                for (hours in weeklyHours.filter { it.dayOfWeek == dow }) {
                    val shiftStart = date.atTime(hours.start).atZone(zoneId).toInstant()
                    val shiftEnd = date.atTime(hours.end).atZone(zoneId).toInstant()
                    tileShift(doctor, shiftStart, shiftEnd, slotDuration, window, timeOff, existingBookings, slots)
                }
                date = date.plusDays(1)
            }
            return slots.sortedBy { it.start }
        }

        /** [LocalDate.dayOfWeek] is 1=Mon..7=Sun; remap to 0=Sun..6=Sat. */
        private fun sundayZeroDayOfWeek(date: LocalDate): Int = date.dayOfWeek.value % 7

        @Suppress("LongParameterList")
        private fun tileShift(
            doctor: DoctorRef,
            shiftStart: Instant,
            shiftEnd: Instant,
            slotDuration: Duration,
            window: ClosedRange<Instant>,
            timeOff: List<TimeOff>,
            existingBookings: List<Slot>,
            sink: MutableList<Slot>,
        ) {
            var slotStart = shiftStart
            while (!slotStart.plus(slotDuration).isAfter(shiftEnd)) {
                val slotEnd = slotStart.plus(slotDuration)
                val inWindow = slotStart in window
                val freeOfTimeOff = timeOff.none { overlaps(slotStart, slotEnd, it.start, it.end) }
                val freeOfBooking = existingBookings.none { overlaps(slotStart, slotEnd, it.start, it.end) }
                if (inWindow && freeOfTimeOff && freeOfBooking) {
                    sink += Slot(doctor, slotStart, slotEnd)
                }
                slotStart = slotEnd
            }
        }

        /** Half-open `[aStart, aEnd)` overlaps half-open `[bStart, bEnd)`. */
        private fun overlaps(aStart: Instant, aEnd: Instant, bStart: Instant, bEnd: Instant): Boolean =
            aStart < bEnd && bStart < aEnd
    }
}
