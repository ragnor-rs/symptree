package com.symptree.core.db

import com.symptree.core.model.Doctor
import com.symptree.core.model.NewDoctor
import com.symptree.core.source.internal.TimeOff
import com.symptree.core.source.internal.WeeklyHours
import java.sql.Connection
import java.sql.ResultSet

/**
 * Raw-JDBC repository for the canonical `doctors` catalog and its singleton
 * `doctor_identity` rows.
 *
 * Per technical-spec.md § 4.4 every tenant-scoped function takes `clinicId: Long`
 * as its first parameter; `(source_id, external_id)` is the upsert key, and the
 * `identity_id` is preserved across re-upserts so downstream references stay stable.
 */
class DoctorRepo(private val database: PostgresDatabase) {

    private val doctorColumns =
        "id, clinic_id, identity_id, source_id, external_id, specialist_codes, slot_minutes, full_name, active"

    private val insertColumns =
        "clinic_id, identity_id, source_id, external_id, specialist_codes, slot_minutes, full_name, active"

    /**
     * Insert a brand-new doctor in one transaction: create its singleton
     * `doctor_identity` first, then the `doctors` row referencing it.
     */
    fun insert(doctor: NewDoctor): Doctor {
        database.getConnection().use { conn ->
            return inTransaction(conn) {
                val identityId = insertIdentity(conn, doctor.clinicId, doctor.fullName)
                insertDoctor(conn, doctor, identityId)
            }
        }
    }

    /**
     * Upsert keyed on `(source_id, external_id)`. On a fresh insert the singleton
     * identity is auto-created; on conflict the existing `identity_id` is kept and
     * only the mutable fields are refreshed.
     *
     * A per-key transaction-scoped advisory lock serializes concurrent upserts of
     * the same `(source_id, external_id)` (the M3 directory-sync path), so two
     * racers cannot both observe "no existing row" and both create an identity —
     * which would leave the loser's freshly-created `doctor_identity` orphaned by
     * the identity-preserving `ON CONFLICT DO UPDATE`. The lock releases at
     * commit/rollback and is a no-op for single-threaded callers.
     */
    fun upsert(doctor: NewDoctor): Doctor {
        database.getConnection().use { conn ->
            return inTransaction(conn) {
                lockUpsertKey(conn, doctor.sourceId, doctor.externalId)
                val existing = findIdentityIdByExternalKey(conn, doctor.sourceId, doctor.externalId)
                val identityId = existing ?: insertIdentity(conn, doctor.clinicId, doctor.fullName)
                upsertDoctor(conn, doctor, identityId)
            }
        }
    }

    fun findById(clinicId: Long, id: Long): Doctor? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT $doctorColumns FROM doctors WHERE id = ? AND clinic_id = ?",
            ).use { ps ->
                ps.setLong(1, id)
                ps.setLong(2, clinicId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toDoctor() else null
                }
            }
        }
    }

    /**
     * Look up a doctor by its source-scoped `(source_id, external_id)` key — the
     * internal scheduler holds only a [com.symptree.core.source.DoctorRef] (no
     * clinicId), so this resolves the persisted row (and thus `clinic_id` and
     * `slot_minutes`) for availability/booking.
     */
    fun findByExternalKey(sourceId: String, externalId: String): Doctor? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT $doctorColumns FROM doctors WHERE source_id = ? AND external_id = ? " +
                    "AND deleted_at IS NULL",
            ).use { ps ->
                ps.setString(1, sourceId)
                ps.setString(2, externalId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toDoctor() else null
                }
            }
        }
    }

    fun listActive(clinicId: Long): List<Doctor> {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT $doctorColumns FROM doctors " +
                    "WHERE clinic_id = ? AND active = TRUE AND deleted_at IS NULL " +
                    "ORDER BY id ASC",
            ).use { ps ->
                ps.setLong(1, clinicId)
                return ps.collectDoctors()
            }
        }
    }

    /**
     * Like [listActive] but surfaces soft-deleted (`deleted_at IS NOT NULL`) and
     * inactive doctors too — the admin panel's "include deleted" view (AC-MG7TSR.4).
     */
    fun listAll(clinicId: Long): List<Doctor> {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT $doctorColumns FROM doctors WHERE clinic_id = ? ORDER BY id ASC",
            ).use { ps ->
                ps.setLong(1, clinicId)
                return ps.collectDoctors()
            }
        }
    }

    /**
     * Clinic-scoped update of a doctor's mutable fields (AC-MG7TSR.4). Returns the
     * refreshed row, or null when no row in this clinic matches `id`. The
     * `(source_id, external_id)` key and `identity_id` are immutable here.
     */
    fun update(
        clinicId: Long,
        id: Long,
        fullName: String,
        specialistCodes: List<String>,
        slotMinutes: Int,
        active: Boolean,
    ): Doctor? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE doctors SET full_name = ?, specialist_codes = ?, slot_minutes = ?, active = ? " +
                    "WHERE id = ? AND clinic_id = ? RETURNING $doctorColumns",
            ).use { ps ->
                ps.setString(1, fullName)
                ps.setArray(2, conn.createArrayOf("text", specialistCodes.toTypedArray()))
                ps.setInt(3, slotMinutes)
                ps.setBoolean(4, active)
                ps.setLong(5, id)
                ps.setLong(6, clinicId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toDoctor() else null
                }
            }
        }
    }

    fun listBySpecialist(clinicId: Long, code: String): List<Doctor> {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT $doctorColumns FROM doctors " +
                    "WHERE clinic_id = ? AND specialist_codes @> ARRAY[?]::text[] " +
                    "AND active = TRUE AND deleted_at IS NULL " +
                    "ORDER BY id ASC",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setString(2, code)
                return ps.collectDoctors()
            }
        }
    }

    fun softDelete(clinicId: Long, id: Long) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE doctors SET deleted_at = NOW() WHERE id = ? AND clinic_id = ?",
            ).use { ps ->
                ps.setLong(1, id)
                ps.setLong(2, clinicId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Recurring weekly availability rows for a doctor (the internal scheduler's
     * `doctor_weekly_hours` inputs). `day_of_week` is 0=Sun..6=Sat in clinic-local
     * time; `start_time`/`end_time` are local wall-clock.
     */
    fun weeklyHours(doctorId: Long): List<WeeklyHours> {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT day_of_week, start_time, end_time FROM doctor_weekly_hours " +
                    "WHERE doctor_id = ? ORDER BY day_of_week, start_time",
            ).use { ps ->
                ps.setLong(1, doctorId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<WeeklyHours>()
                    while (rs.next()) {
                        out += WeeklyHours(
                            dayOfWeek = rs.getInt("day_of_week"),
                            start = rs.getTime("start_time").toLocalTime(),
                            end = rs.getTime("end_time").toLocalTime(),
                        )
                    }
                    return out
                }
            }
        }
    }

    /**
     * Replace the doctor's entire weekly-hours set in one transaction (AC-MG7TSR.5):
     * delete all existing `doctor_weekly_hours` rows for the doctor, then insert the
     * provided [rows]. Split shifts (multiple intervals per weekday) are allowed.
     */
    fun replaceWeeklyHours(doctorId: Long, rows: List<WeeklyHours>) {
        database.getConnection().use { conn ->
            inTransaction(conn) {
                conn.prepareStatement(
                    "DELETE FROM doctor_weekly_hours WHERE doctor_id = ?",
                ).use { ps ->
                    ps.setLong(1, doctorId)
                    ps.executeUpdate()
                }
                if (rows.isNotEmpty()) {
                    conn.prepareStatement(
                        "INSERT INTO doctor_weekly_hours (doctor_id, day_of_week, start_time, end_time) " +
                            "VALUES (?, ?, ?, ?)",
                    ).use { ps ->
                        for (row in rows) {
                            ps.setLong(1, doctorId)
                            ps.setInt(2, row.dayOfWeek)
                            ps.setTime(3, java.sql.Time.valueOf(row.start))
                            ps.setTime(4, java.sql.Time.valueOf(row.end))
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
            }
        }
    }

    /** A time-off window with its persisted id (the admin-panel list/delete view, AC-MG7TSR.5). */
    data class TimeOffRow(val id: Long, val start: java.time.Instant, val end: java.time.Instant, val reason: String?)

    /** Insert a one-off unavailability window and return its generated id (AC-MG7TSR.5). */
    fun addTimeOff(doctorId: Long, start: java.time.Instant, end: java.time.Instant, reason: String?): Long {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO doctor_time_off (doctor_id, start_at, end_at, reason) " +
                    "VALUES (?, ?, ?, ?) RETURNING id",
            ).use { ps ->
                ps.setLong(1, doctorId)
                ps.setTimestamp(2, java.sql.Timestamp.from(start))
                ps.setTimestamp(3, java.sql.Timestamp.from(end))
                ps.setString(4, reason)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT ... RETURNING produced no row" }
                    return rs.getLong("id")
                }
            }
        }
    }

    /** List a doctor's time-off windows with ids (AC-MG7TSR.5). */
    fun listTimeOff(doctorId: Long): List<TimeOffRow> {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, start_at, end_at, reason FROM doctor_time_off " +
                    "WHERE doctor_id = ? ORDER BY start_at",
            ).use { ps ->
                ps.setLong(1, doctorId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<TimeOffRow>()
                    while (rs.next()) {
                        out += TimeOffRow(
                            id = rs.getLong("id"),
                            start = rs.getTimestamp("start_at").toInstant(),
                            end = rs.getTimestamp("end_at").toInstant(),
                            reason = rs.getString("reason"),
                        )
                    }
                    return out
                }
            }
        }
    }

    /** Delete one time-off window by id, scoped to the doctor (AC-MG7TSR.5). */
    fun deleteTimeOff(doctorId: Long, timeOffId: Long) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM doctor_time_off WHERE id = ? AND doctor_id = ?",
            ).use { ps ->
                ps.setLong(1, timeOffId)
                ps.setLong(2, doctorId)
                ps.executeUpdate()
            }
        }
    }

    /** One-off unavailability windows for a doctor (`doctor_time_off`), as absolute UTC ranges. */
    fun timeOff(doctorId: Long): List<TimeOff> {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT start_at, end_at FROM doctor_time_off WHERE doctor_id = ? ORDER BY start_at",
            ).use { ps ->
                ps.setLong(1, doctorId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<TimeOff>()
                    while (rs.next()) {
                        out += TimeOff(
                            start = rs.getTimestamp("start_at").toInstant(),
                            end = rs.getTimestamp("end_at").toInstant(),
                        )
                    }
                    return out
                }
            }
        }
    }

    // ---------------- internals ----------------

    private fun <T> inTransaction(conn: Connection, body: () -> T): T {
        val priorAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = body()
            conn.commit()
            return result
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = priorAutoCommit
        }
    }

    /**
     * Serialize concurrent upserts of one `(source_id, external_id)` via a
     * transaction-scoped advisory lock (auto-released at commit/rollback).
     */
    private fun lockUpsertKey(conn: Connection, sourceId: String, externalId: String) {
        conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { ps ->
            ps.setString(1, "$sourceId|$externalId")
            ps.executeQuery().use { it.next() }
        }
    }

    private fun insertIdentity(conn: Connection, clinicId: Long, displayName: String): Long {
        conn.prepareStatement(
            "INSERT INTO doctor_identity (clinic_id, display_name) VALUES (?, ?) RETURNING id",
        ).use { ps ->
            ps.setLong(1, clinicId)
            ps.setString(2, displayName)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                return rs.getLong("id")
            }
        }
    }

    private fun findIdentityIdByExternalKey(conn: Connection, sourceId: String, externalId: String): Long? {
        conn.prepareStatement(
            "SELECT identity_id FROM doctors WHERE source_id = ? AND external_id = ?",
        ).use { ps ->
            ps.setString(1, sourceId)
            ps.setString(2, externalId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getLong("identity_id") else null
            }
        }
    }

    private fun insertDoctor(conn: Connection, doctor: NewDoctor, identityId: Long): Doctor {
        val sql = """
            INSERT INTO doctors ($insertColumns)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING $doctorColumns
        """.trimIndent()
        return writeDoctor(conn, sql, doctor, identityId)
    }

    private fun upsertDoctor(conn: Connection, doctor: NewDoctor, identityId: Long): Doctor {
        val sql = """
            INSERT INTO doctors ($insertColumns)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_id, external_id) DO UPDATE SET
                full_name        = EXCLUDED.full_name,
                specialist_codes = EXCLUDED.specialist_codes,
                slot_minutes     = EXCLUDED.slot_minutes,
                active           = EXCLUDED.active
            RETURNING $doctorColumns
        """.trimIndent()
        return writeDoctor(conn, sql, doctor, identityId)
    }

    /**
     * Bind the 8 `doctors` write columns in declaration order and return the
     * `RETURNING`-projected row. Shared by [insertDoctor] and [upsertDoctor].
     */
    private fun writeDoctor(conn: Connection, sql: String, doctor: NewDoctor, identityId: Long): Doctor {
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, doctor.clinicId)
            ps.setLong(2, identityId)
            ps.setString(3, doctor.sourceId)
            ps.setString(4, doctor.externalId)
            ps.setArray(5, conn.createArrayOf("text", doctor.specialistCodes.toTypedArray()))
            ps.setInt(6, doctor.slotMinutes)
            ps.setString(7, doctor.fullName)
            ps.setBoolean(8, doctor.active)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                return rs.toDoctor()
            }
        }
    }

    private fun java.sql.PreparedStatement.collectDoctors(): List<Doctor> {
        executeQuery().use { rs ->
            val out = mutableListOf<Doctor>()
            while (rs.next()) out += rs.toDoctor()
            return out
        }
    }

    private fun ResultSet.toDoctor(): Doctor {
        val rawCodes = getArray("specialist_codes").array as Array<*>
        return Doctor(
            id = getLong("id"),
            clinicId = getLong("clinic_id"),
            identityId = getLong("identity_id"),
            sourceId = getString("source_id"),
            externalId = getString("external_id"),
            fullName = getString("full_name"),
            specialistCodes = rawCodes.filterIsInstance<String>(),
            slotMinutes = getInt("slot_minutes"),
            active = getBoolean("active"),
        )
    }
}
