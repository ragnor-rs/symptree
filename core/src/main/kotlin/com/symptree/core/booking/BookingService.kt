package com.symptree.core.booking

import com.symptree.core.scheduler.ReminderKind
import com.symptree.core.source.BookingProvider
import com.symptree.core.source.DoctorRef
import com.symptree.core.source.PatientRef
import com.symptree.core.source.SchedulingProvider
import com.symptree.core.source.Slot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Sink for reminder rows the [BookingService] schedules around a confirmed
 * booking. FR-VWRNDR — carries **semantic** fields (typed [kind], doctor name,
 * raw slot [Instant], clinic zone); the `messaging` frontend maps the kind to a
 * catalog key and formats the slot. No catalog key or view format here.
 */
interface ReminderSink {
    @Suppress("LongParameterList")
    fun insert(
        sessionId: Long,
        clinicId: Long,
        kind: ReminderKind,
        dueAt: Instant,
        doctorName: String,
        slotAt: Instant,
        clinicZone: String,
    )
}

/** Outcome of a [BookingService] confirm/decline interaction. */
sealed interface BookingOutcome {
    data class Confirmed(val slot: Slot) : BookingOutcome
    data class ReProposed(val slot: Slot) : BookingOutcome
    data object ContactClinic : BookingOutcome
    data object NoSlots : BookingOutcome
}

/** Thrown by a [BookingProvider] when a slot was taken between proposal and booking. */
class SlotTakenException(
    val externalDoctorId: String,
    val slotStart: Instant,
    val slotEnd: Instant,
) : RuntimeException("slot $slotStart..$slotEnd for $externalDoctorId already taken")

/**
 * Orchestrates auto-booking: find the earliest slot for a specialist, confirm it
 * (with re-propose on race), or decline-and-re-propose up to [maxDeclines].
 *
 * Stubbed for AC-W03362.1 compile; fleshed out in AC-W03362.5/.6/.7.
 */
class BookingService(
    private val scheduling: SchedulingProvider,
    private val booking: BookingProvider,
    private val doctors: List<DoctorRef>,
    private val reminders: ReminderSink,
    private val clock: Clock,
    private val maxDeclines: Int,
    /**
     * Minimum booking lead time (AC-W03362.13): [findFirstAvailable] and the
     * decline re-propose never offer a slot earlier than `now() + minLead`.
     * Defaults to [Duration.ZERO] (propose the literal earliest slot); production
     * wires the per-clinic config value (`preferences.min_lead_hours`, default
     * 24h). `ZERO` re-enables same-day/near-now proposals.
     */
    private val minLead: Duration = Duration.ZERO,
    /**
     * The clinic's IANA scheduling zone (`clinics.config.preferences.timezone`,
     * default `UTC`). Reminder `time`/`date` params are formatted clinic-local so
     * the messaging-side catalog (Variant A) renders a human time, not a raw UTC
     * ISO instant. DST-correct via [ZoneId].
     */
    private val zone: String = "UTC",
    private val strategy: SlotStrategy = FirstAvailable,
) {
    /**
     * Finds the earliest bookable slot across active doctors whose
     * `specialistCodes` contain [specialistCode], querying [scheduling]
     * availability over `now()..now()+lookahead` and delegating slot selection
     * to the pluggable [strategy] (AC-W03362.2).
     */
    // `clinicId` is retained per the tenancy convention (technical-spec §4.4: every tenant-scoped fn
    // takes clinicId first) and reserved for future per-clinic doctor resolution; the current MVP
    // resolves doctors from the pre-filtered [doctors] list, so the param is intentionally unused here.
    @Suppress("UnusedParameter")
    suspend fun findFirstAvailable(clinicId: Long, specialistCode: String, lookahead: Duration): Slot? {
        val now = clock.instant()
        // AC-W03362.13 — never propose a slot inside the minimum lead window.
        val window = now.plus(minLead)..now.plus(lookahead)
        val candidates = doctors.filter { specialistCode in it.specialistCodes }
        if (candidates.isEmpty()) return null
        val perDoctor = candidates.associateWith { scheduling.availability(it, window) }
        return strategy.pick(perDoctor)
    }

    @Suppress("LongParameterList")
    suspend fun confirm(
        clinicId: Long,
        sessionId: Long,
        slot: Slot,
        patient: PatientRef,
        specialistCode: String,
    ): BookingOutcome {
        try {
            booking.book(slot, patient)
        } catch (e: SlotTakenException) {
            val next = findFirstAvailable(clinicId, specialistCode, RE_PROPOSE_LOOKAHEAD)
                ?: return BookingOutcome.NoSlots
            return BookingOutcome.ReProposed(next)
        }

        scheduleReminders(clinicId, sessionId, slot)
        return BookingOutcome.Confirmed(slot)
    }

    private fun scheduleReminders(clinicId: Long, sessionId: Long, slot: Slot) {
        val now = clock.instant()
        // FR-VWRNDR — emit semantic fields only: the typed kind, the doctor name,
        // the raw slot Instant and the clinic zone. The messaging frontend maps
        // the kind to a catalog key and formats the slot clinic-local at dispatch.
        val doctorName = slot.doctorRef.displayName

        // Classify by the CLINIC-LOCAL calendar date, not a rolling 24h window: a
        // slot that is <24h out but on the NEXT calendar day is a "завтра/ертең"
        // pre-visit, not a "сегодня/бүгін" same-day reminder. When the 24h-before
        // instant is already past (a near-now booking the day before), the
        // pre-visit fires promptly (now+delay) instead of at a past instant.
        val zoneId = ZoneId.of(zone)
        val slotDate = slot.start.atZone(zoneId).toLocalDate()
        val todayDate = now.atZone(zoneId).toLocalDate()
        val preVisitAt = slot.start.minus(PRE_VISIT_LEAD)
        if (slotDate == todayDate) {
            reminders.insert(
                sessionId = sessionId,
                clinicId = clinicId,
                kind = ReminderKind.SAME_DAY,
                dueAt = now.plus(SAME_DAY_DELAY),
                doctorName = doctorName,
                slotAt = slot.start,
                clinicZone = zone,
            )
        } else {
            reminders.insert(
                sessionId = sessionId,
                clinicId = clinicId,
                kind = ReminderKind.PRE_VISIT_24H,
                dueAt = if (preVisitAt.isBefore(now)) now.plus(SAME_DAY_DELAY) else preVisitAt,
                doctorName = doctorName,
                slotAt = slot.start,
                clinicZone = zone,
            )
        }

        reminders.insert(
            sessionId = sessionId,
            clinicId = clinicId,
            // AC-W03362.10 — when this reminder lands the bot sends the feedback
            // prompt (1–5); messaging renders bot.feedback.prompt off this kind.
            kind = ReminderKind.POST_VISIT_FEEDBACK,
            dueAt = slot.end.plus(POST_VISIT_LEAD),
            doctorName = doctorName,
            slotAt = slot.start,
            clinicZone = zone,
        )
    }

    /**
     * Patient declined a slot: re-run [findFirstAvailable] excluding **every** slot
     * declined so far this conversation ([declinedStarts]) and re-propose the
     * next-earliest. Excluding the whole set — not just the latest — is what stops
     * the re-proposal cycling back to an already-rejected time (FR-W03362 AC.7).
     * After [maxDeclines] declines fall back to [BookingOutcome.ContactClinic]
     * (the caller flips the session to `concluded`).
     */
    // `clinicId` / `sessionId` are retained per the tenancy convention (technical-spec §4.4) to keep
    // the booking API stable and symmetric with [confirm]; the re-propose path is keyed by doctor +
    // declined slots only, so neither is read here yet (reserved for per-clinic doctor resolution).
    @Suppress("LongParameterList", "UnusedParameter")
    suspend fun decline(
        clinicId: Long,
        sessionId: Long,
        declinedStarts: Set<Instant>,
        specialistCode: String,
        declineCount: Int,
    ): BookingOutcome {
        if (declineCount >= maxDeclines) return BookingOutcome.ContactClinic

        val now = clock.instant()
        // AC-W03362.13 — the re-propose honors the same minimum lead window.
        val window = now.plus(minLead)..now.plus(RE_PROPOSE_LOOKAHEAD)
        val candidates = doctors.filter { specialistCode in it.specialistCodes }
        val perDoctor = candidates.associateWith { doctor ->
            scheduling.availability(doctor, window).filterNot { it.start in declinedStarts }
        }
        val next = strategy.pick(perDoctor) ?: return BookingOutcome.ContactClinic
        return BookingOutcome.ReProposed(next)
    }

    private companion object {
        val PRE_VISIT_LEAD: Duration = Duration.ofHours(24)
        val POST_VISIT_LEAD: Duration = Duration.ofHours(2)
        val SAME_DAY_DELAY: Duration = Duration.ofMinutes(1)
        val RE_PROPOSE_LOOKAHEAD: Duration = Duration.ofDays(30)
    }
}
