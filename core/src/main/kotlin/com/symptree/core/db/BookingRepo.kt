package com.symptree.core.db

import com.symptree.core.booking.FeedbackSink
import com.symptree.core.booking.SlotTakenException
import com.symptree.core.source.PatientRef
import com.symptree.core.source.Slot
import org.postgresql.util.PSQLException
import java.sql.Timestamp
import java.time.Instant

/** A persisted `bookings` row (the subset the booking flow reads back). */
data class Booking(
    val id: Long,
    val clinicId: Long,
    val sessionId: Long,
    val sourceId: String,
    val externalDoctorId: String,
    val slotAt: Instant,
    val slotEnd: Instant,
    val externalAppointmentId: String?,
    val status: String,
)

/** AC-MG7TSR.7 — one admin-list booking row (doctor display name + slot + status + feedback). */
data class BookingListRow(
    val id: Long,
    val doctorName: String,
    val slotAt: Instant,
    val slotEnd: Instant,
    val status: String,
    val feedbackScore: Int?,
)

/** AC-MG7TSR.7 — the bits of a just-cancelled booking the notifier needs. */
data class CancelledBooking(
    val sessionId: Long,
    val doctorName: String,
    val slotAt: Instant,
)

/**
 * Raw-JDBC repository for the source-agnostic `bookings` table.
 *
 * Per technical-spec.md § 4.4 every tenant-scoped function takes `clinicId: Long`
 * first. The internal-source double-booking guard (migration 07's partial
 * `EXCLUDE USING gist`) raises Postgres SQLState `23P01` (exclusion_violation) on
 * a racing overlap; [insert] maps that to the typed [SlotTakenException].
 */
class BookingRepo(private val database: PostgresDatabase) {

    /** Postgres SQLState for an `EXCLUDE` (exclusion) constraint violation. */
    private val exclusionViolation = "23P01"

    /**
     * Insert a confirmed booking row for [slot], owned by the active session of
     * [patient] within [clinicId]. `external_appointment_id` is NULL for the
     * internal source (symptree is the calendar of record). A racing overlap on
     * the internal `EXCLUDE` guard surfaces as [SlotTakenException].
     */
    fun insert(clinicId: Long, slot: Slot, patient: PatientRef): Booking {
        val sessionId = resolveSessionId(clinicId, patient)
        val sql = """
            INSERT INTO bookings
                (clinic_id, session_id, source_id, external_doctor_id, doctor_identity_id,
                 specialist_code, slot_at, slot_end, external_appointment_id, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 'confirmed')
            RETURNING id, clinic_id, session_id, source_id, external_doctor_id,
                      slot_at, slot_end, external_appointment_id, status
        """.trimIndent()
        database.getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.setString(3, slot.doctorRef.sourceId.value)
                ps.setString(4, slot.doctorRef.externalId)
                ps.setLong(5, slot.doctorRef.identityId)
                ps.setString(6, slot.doctorRef.specialistCodes.firstOrNull() ?: "")
                ps.setTimestamp(7, Timestamp.from(slot.start))
                ps.setTimestamp(8, Timestamp.from(slot.end))
                try {
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "INSERT ... RETURNING produced no row" }
                        return rs.toBooking()
                    }
                } catch (e: PSQLException) {
                    if (e.sqlState == exclusionViolation) {
                        throw SlotTakenException(slot.doctorRef.externalId, slot.start, slot.end)
                    }
                    throw e
                }
            }
        }
    }

    /** Resolve the most-recent active session for the patient's external id within the clinic. */
    private fun resolveSessionId(clinicId: Long, patient: PatientRef): Long {
        val externalId = patient.externalId
            ?: error("internal booking requires a patient externalId to bind the session")
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id FROM sessions WHERE clinic_id = ? AND platform_user_id = ? " +
                    "ORDER BY (status = 'active') DESC, updated_at DESC LIMIT 1",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setString(2, externalId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "no session for clinic=$clinicId patient=$externalId" }
                    return rs.getLong("id")
                }
            }
        }
    }

    /** Mark the matching confirmed internal booking row cancelled. */
    fun cancel(clinicId: Long, externalDoctorId: String, slotAt: Instant) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE bookings SET status = 'cancelled', updated_at = NOW() " +
                    "WHERE clinic_id = ? AND source_id = 'internal' AND external_doctor_id = ? " +
                    "AND slot_at = ? AND status = 'confirmed'",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setString(2, externalDoctorId)
                ps.setTimestamp(3, Timestamp.from(slotAt))
                ps.executeUpdate()
            }
        }
    }

    fun confirmedBookings(clinicId: Long, externalDoctorId: String): List<Booking> {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, clinic_id, session_id, source_id, external_doctor_id, " +
                    "slot_at, slot_end, external_appointment_id, status FROM bookings " +
                    "WHERE clinic_id = ? AND source_id = 'internal' AND external_doctor_id = ? " +
                    "AND status = 'confirmed' ORDER BY slot_at",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setString(2, externalDoctorId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Booking>()
                    while (rs.next()) out += rs.toBooking()
                    return out
                }
            }
        }
    }

    /**
     * AC-MG7TSR.7 — clinic-scoped booking list for the admin panel. Joins the
     * doctor display name (`doctor_identity.display_name`, falling back to the
     * booking's `external_doctor_id` when no identity is attached) and carries
     * slot window, status and feedback score. Filterable by [status] and a
     * `slot_at` window (`[from, to)`).
     */
    fun listForClinic(
        clinicId: Long,
        status: String? = null,
        from: Instant? = null,
        to: Instant? = null,
    ): List<BookingListRow> {
        val sql = buildString {
            append(
                "SELECT b.id, b.status, b.slot_at, b.slot_end, b.feedback_score, " +
                    "COALESCE(di.display_name, b.external_doctor_id) AS doctor_name " +
                    "FROM bookings b " +
                    "LEFT JOIN doctor_identity di ON di.id = b.doctor_identity_id " +
                    "WHERE b.clinic_id = ?",
            )
            if (status != null) append(" AND b.status = ?")
            if (from != null) append(" AND b.slot_at >= ?")
            if (to != null) append(" AND b.slot_at < ?")
            append(" ORDER BY b.slot_at ASC, b.id ASC")
        }
        database.getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setLong(i++, clinicId)
                if (status != null) ps.setString(i++, status)
                if (from != null) ps.setTimestamp(i++, Timestamp.from(from))
                if (to != null) ps.setTimestamp(i++, Timestamp.from(to))
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<BookingListRow>()
                    while (rs.next()) {
                        val score = rs.getInt("feedback_score").let { if (rs.wasNull()) null else it }
                        out += BookingListRow(
                            id = rs.getLong("id"),
                            doctorName = rs.getString("doctor_name"),
                            slotAt = rs.getTimestamp("slot_at").toInstant(),
                            slotEnd = rs.getTimestamp("slot_end").toInstant(),
                            status = rs.getString("status"),
                            feedbackScore = score,
                        )
                    }
                    return out
                }
            }
        }
    }

    /**
     * AC-MG7TSR.7 — admin cancel. Flips a `'confirmed'` booking (scoped to
     * [clinicId]) to `'cancelled'` so the internal `EXCLUDE` guard releases the
     * slot, returning the cancelled row's session id, doctor display name and
     * slot start for the patient notification. Returns null when no confirmed
     * row matched (already-cancelled / completed) — the caller answers 409.
     */
    fun cancelById(clinicId: Long, bookingId: Long): CancelledBooking? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE bookings b SET status = 'cancelled', updated_at = NOW() " +
                    "FROM (SELECT id, doctor_identity_id, external_doctor_id, session_id, slot_at " +
                    "      FROM bookings WHERE id = ? AND clinic_id = ?) src " +
                    "LEFT JOIN doctor_identity di ON di.id = src.doctor_identity_id " +
                    "WHERE b.id = src.id AND b.status = 'confirmed' " +
                    "RETURNING b.session_id, " +
                    "COALESCE(di.display_name, src.external_doctor_id) AS doctor_name, b.slot_at",
            ).use { ps ->
                ps.setLong(1, bookingId)
                ps.setLong(2, clinicId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        CancelledBooking(
                            sessionId = rs.getLong("session_id"),
                            doctorName = rs.getString("doctor_name"),
                            slotAt = rs.getTimestamp("slot_at").toInstant(),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * AC-MG7TSR.3 — tenancy guard for the admin REST surface: true iff a booking
     * row with [bookingId] exists AND belongs to [clinicId]. A foreign or absent
     * id is indistinguishable (both false), so the caller answers 403 either way
     * and never leaks existence as a 404.
     */
    fun existsForClinic(clinicId: Long, bookingId: Long): Boolean {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM bookings WHERE id = ? AND clinic_id = ?",
            ).use { ps ->
                ps.setLong(1, bookingId)
                ps.setLong(2, clinicId)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    fun latestForSession(clinicId: Long, sessionId: Long): Booking? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, clinic_id, session_id, source_id, external_doctor_id, " +
                    "slot_at, slot_end, external_appointment_id, status FROM bookings " +
                    "WHERE clinic_id = ? AND session_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toBooking() else null
                }
            }
        }
    }

    private fun java.sql.ResultSet.toBooking(): Booking = Booking(
        id = getLong("id"),
        clinicId = getLong("clinic_id"),
        sessionId = getLong("session_id"),
        sourceId = getString("source_id"),
        externalDoctorId = getString("external_doctor_id"),
        slotAt = getTimestamp("slot_at").toInstant(),
        slotEnd = getTimestamp("slot_end").toInstant(),
        externalAppointmentId = getString("external_appointment_id"),
        status = getString("status"),
    )

    companion object {
        /** Adapts a [BookingRepo] into a [FeedbackSink] bound to one clinic. */
        fun feedbackSink(repo: BookingRepo, clinicId: Long): FeedbackSink =
            object : FeedbackSink {
                override fun recordScore(bookingId: Long, score: Int, text: String?) {
                    repo.recordScore(clinicId, bookingId, score, text)
                }
            }
    }

    private fun recordScore(clinicId: Long, bookingId: Long, score: Int, text: String?) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE bookings SET feedback_score = ?, feedback_text = ?, updated_at = NOW() " +
                    "WHERE clinic_id = ? AND id = ?",
            ).use { ps ->
                ps.setInt(1, score)
                ps.setString(2, text)
                ps.setLong(3, clinicId)
                ps.setLong(4, bookingId)
                ps.executeUpdate()
            }
        }
    }
}
