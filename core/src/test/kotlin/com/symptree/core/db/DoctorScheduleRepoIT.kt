package com.symptree.core.db

import com.symptree.core.Fixtures
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewDoctor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-YDANPW.5 — doctor_weekly_hours + doctor_time_off schema (Testcontainers).
 *
 *  - multiple rows per (doctor_id, day_of_week) express split shifts,
 *  - `CHECK (end_time > start_time)` and `CHECK (end_at > start_at)` reject inverted ranges.
 */
class DoctorScheduleRepoIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var doctorRepo: DoctorRepo
    private var doctorId: Long = 0L

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        clinicRepo = ClinicRepo(PostgresTestSupport.database())
        doctorRepo = DoctorRepo(PostgresTestSupport.database())
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "sched").clinic
        doctorId = doctorRepo.insert(
            NewDoctor(
                clinicId = clinic.id,
                sourceId = "internal",
                externalId = "sched-doc",
                fullName = "Dr Sched",
                specialistCodes = listOf("therapist"),
                slotMinutes = 30,
                active = true,
            ),
        ).id
    }

    private fun columns(table: String): Map<String, String> {
        val seen = mutableMapOf<String, String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema='public' AND table_name=?",
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs ->
                    while (rs.next()) seen[rs.getString(1)] = rs.getString(2)
                }
            }
        }
        return seen
    }

    private fun insertWeeklyHours(conn: Connection, day: Int, start: String, end: String) {
        conn.prepareStatement(
            "INSERT INTO doctor_weekly_hours (doctor_id, day_of_week, start_time, end_time) " +
                "VALUES (?, ?, ?::time, ?::time)",
        ).use { ps ->
            ps.setLong(1, doctorId)
            ps.setInt(2, day)
            ps.setString(3, start)
            ps.setString(4, end)
            ps.executeUpdate()
        }
    }

    private fun insertTimeOff(conn: Connection, start: String, end: String) {
        conn.prepareStatement(
            "INSERT INTO doctor_time_off (doctor_id, start_at, end_at, reason) " +
                "VALUES (?, ?::timestamptz, ?::timestamptz, ?)",
        ).use { ps ->
            ps.setLong(1, doctorId)
            ps.setString(2, start)
            ps.setString(3, end)
            ps.setString(4, "vacation")
            ps.executeUpdate()
        }
    }

    // ---------------- schema ----------------

    @Test
    fun `AC-5 doctor_weekly_hours has required columns`() {
        val cols = columns("doctor_weekly_hours")
        val expected = mapOf(
            "id" to "bigint",
            "doctor_id" to "bigint",
            "day_of_week" to "smallint",
            "start_time" to "time without time zone",
            "end_time" to "time without time zone",
        )
        for ((col, type) in expected) {
            val actual = cols[col] ?: fail("doctor_weekly_hours missing column $col; saw: $cols")
            assertEquals(type, actual, "doctor_weekly_hours.$col unexpected type")
        }
    }

    @Test
    fun `AC-5 doctor_time_off has required columns`() {
        val cols = columns("doctor_time_off")
        val expected = mapOf(
            "id" to "bigint",
            "doctor_id" to "bigint",
            "start_at" to "timestamp with time zone",
            "end_at" to "timestamp with time zone",
            "reason" to "text",
        )
        for ((col, type) in expected) {
            val actual = cols[col] ?: fail("doctor_time_off missing column $col; saw: $cols")
            assertEquals(type, actual, "doctor_time_off.$col unexpected type")
        }
    }

    // ---------------- split shifts ----------------

    @Test
    fun `AC-5 multiple weekly_hours rows per day_of_week are allowed for split shifts`() {
        PostgresTestSupport.database().getConnection().use { conn ->
            insertWeeklyHours(conn, day = 1, start = "09:00", end = "12:00")
            insertWeeklyHours(conn, day = 1, start = "14:00", end = "18:00")
        }
        val count = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM doctor_weekly_hours WHERE doctor_id=? AND day_of_week=1",
            ).use { ps ->
                ps.setLong(1, doctorId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(2, count, "two split-shift rows on the same day must coexist")
    }

    // ---------------- CHECK constraints ----------------

    @Test
    fun `AC-5 weekly_hours rejects inverted range via CHECK end_time greater start_time`() {
        try {
            PostgresTestSupport.database().getConnection().use { conn ->
                insertWeeklyHours(conn, day = 2, start = "18:00", end = "09:00")
            }
            fail("expected CHECK (end_time > start_time) to reject inverted range")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("check", ignoreCase = true) == true ||
                    e.cause?.message?.contains("check", ignoreCase = true) == true,
                "expected check-constraint violation, got: ${e.message}",
            )
        }
    }

    @Test
    fun `AC-5 time_off rejects inverted range via CHECK end_at greater start_at`() {
        try {
            PostgresTestSupport.database().getConnection().use { conn ->
                insertTimeOff(conn, start = "2026-07-10T10:00:00Z", end = "2026-07-09T10:00:00Z")
            }
            fail("expected CHECK (end_at > start_at) to reject inverted range")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("check", ignoreCase = true) == true ||
                    e.cause?.message?.contains("check", ignoreCase = true) == true,
                "expected check-constraint violation, got: ${e.message}",
            )
        }
    }

    @Test
    fun `AC-5 valid weekly_hours and time_off rows insert cleanly`() {
        PostgresTestSupport.database().getConnection().use { conn ->
            insertWeeklyHours(conn, day = 3, start = "08:00", end = "16:00")
            insertTimeOff(conn, start = "2026-08-01T00:00:00Z", end = "2026-08-08T00:00:00Z")
        }
    }
}
