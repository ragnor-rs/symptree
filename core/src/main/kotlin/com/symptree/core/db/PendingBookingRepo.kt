package com.symptree.core.db

import com.symptree.core.source.DoctorRef
import com.symptree.core.source.Slot
import com.symptree.core.source.SourceId
import java.sql.Timestamp
import java.time.Instant

/**
 * A persisted `pending_bookings` row: the slot currently proposed to a session's
 * patient, awaiting confirm/decline, plus how many times they have declined.
 */
data class PendingBooking(
    val id: Long,
    val clinicId: Long,
    val sessionId: Long,
    val specialistCode: String,
    val sourceId: String,
    val externalDoctorId: String,
    val doctorIdentityId: Long?,
    val doctorDisplayName: String,
    val slotAt: Instant,
    val slotEnd: Instant,
    val declineCount: Int,
    /** Every `slot_at` the patient has declined in this conversation (FR-W03362 AC.7). */
    val declinedSlots: List<Instant> = emptyList(),
) {
    /** Reconstruct the source-agnostic [Slot] this proposal refers to. */
    fun toSlot(): Slot = Slot(
        doctorRef = DoctorRef(
            sourceId = SourceId(sourceId),
            externalId = externalDoctorId,
            identityId = doctorIdentityId ?: 0L,
            displayName = doctorDisplayName,
            specialistCodes = listOf(specialistCode),
        ),
        start = slotAt,
        end = slotEnd,
    )
}

/**
 * Raw-JDBC repository for the transient `pending_bookings` table (AC-W03362.5).
 *
 * One pending proposal per session (UNIQUE session_id): [propose] upserts the
 * slot, preserving [PendingBooking.declineCount] across re-proposals so the
 * N-decline fallback (AC-W03362.7) counts correctly. Per technical-spec.md § 4.4
 * every function takes `clinicId: Long` first.
 */
class PendingBookingRepo(private val database: PostgresDatabase) {

    /** Upsert the proposed [slot] for [sessionId], leaving `decline_count` untouched on conflict. */
    fun propose(clinicId: Long, sessionId: Long, slot: Slot, specialistCode: String): PendingBooking {
        val ref = slot.doctorRef
        val sql = """
            INSERT INTO pending_bookings
                (clinic_id, session_id, specialist_code, source_id, external_doctor_id,
                 doctor_identity_id, doctor_display_name, slot_at, slot_end)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (session_id) DO UPDATE SET
                specialist_code     = EXCLUDED.specialist_code,
                source_id           = EXCLUDED.source_id,
                external_doctor_id  = EXCLUDED.external_doctor_id,
                doctor_identity_id  = EXCLUDED.doctor_identity_id,
                doctor_display_name = EXCLUDED.doctor_display_name,
                slot_at             = EXCLUDED.slot_at,
                slot_end            = EXCLUDED.slot_end,
                updated_at          = NOW()
            RETURNING id, clinic_id, session_id, specialist_code, source_id, external_doctor_id,
                      doctor_identity_id, doctor_display_name, slot_at, slot_end, decline_count,
                      declined_slots
        """.trimIndent()
        database.getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.setString(3, specialistCode)
                ps.setString(4, ref.sourceId.value)
                ps.setString(5, ref.externalId)
                ps.setLong(6, ref.identityId)
                ps.setString(7, ref.displayName)
                ps.setTimestamp(8, Timestamp.from(slot.start))
                ps.setTimestamp(9, Timestamp.from(slot.end))
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT ... RETURNING produced no row" }
                    return rs.toPendingBooking()
                }
            }
        }
    }

    fun find(clinicId: Long, sessionId: Long): PendingBooking? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, clinic_id, session_id, specialist_code, source_id, external_doctor_id, " +
                    "doctor_identity_id, doctor_display_name, slot_at, slot_end, decline_count, " +
                    "declined_slots " +
                    "FROM pending_bookings WHERE clinic_id = ? AND session_id = ?",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toPendingBooking() else null
                }
            }
        }
    }

    /**
     * Record a decline of the session's currently-proposed slot: bump
     * `decline_count` AND append the current `slot_at` to `declined_slots`, then
     * return the updated row. The re-propose then excludes the full declined set
     * so a "🔄 Другое время" never cycles back to an already-rejected time
     * (FR-W03362 AC.7).
     */
    fun incrementDecline(clinicId: Long, sessionId: Long): PendingBooking {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE pending_bookings SET decline_count = decline_count + 1, " +
                    "declined_slots = array_append(declined_slots, slot_at), updated_at = NOW() " +
                    "WHERE clinic_id = ? AND session_id = ? " +
                    "RETURNING id, clinic_id, session_id, specialist_code, source_id, external_doctor_id, " +
                    "doctor_identity_id, doctor_display_name, slot_at, slot_end, decline_count, declined_slots",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "no pending booking to decline for session $sessionId" }
                    return rs.toPendingBooking()
                }
            }
        }
    }

    fun delete(clinicId: Long, sessionId: Long) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM pending_bookings WHERE clinic_id = ? AND session_id = ?",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.toPendingBooking(): PendingBooking {
        val identity = getLong("doctor_identity_id").let { if (wasNull()) null else it }
        return PendingBooking(
            id = getLong("id"),
            clinicId = getLong("clinic_id"),
            sessionId = getLong("session_id"),
            specialistCode = getString("specialist_code"),
            sourceId = getString("source_id"),
            externalDoctorId = getString("external_doctor_id"),
            doctorIdentityId = identity,
            doctorDisplayName = getString("doctor_display_name"),
            slotAt = getTimestamp("slot_at").toInstant(),
            slotEnd = getTimestamp("slot_end").toInstant(),
            declineCount = getInt("decline_count"),
            declinedSlots = (getArray("declined_slots").array as Array<*>)
                .filterIsInstance<Timestamp>()
                .map { it.toInstant() },
        )
    }
}
