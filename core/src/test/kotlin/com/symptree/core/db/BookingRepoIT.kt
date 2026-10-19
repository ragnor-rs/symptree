package com.symptree.core.db

import com.symptree.core.Fixtures
import com.symptree.core.integration.PostgresTestSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-W03362.3 + .4 — `07-migrate-bookings.sql`: source-agnostic `bookings`
 * schema (no `hl7_*`), nullable `external_appointment_id`, and the partial
 * `EXCLUDE USING gist` double-booking guard scoped to the internal source.
 *
 * IT pattern mirrors `DoctorScheduleRepoIT`: column introspection + constraint
 * rejection against a fresh Testcontainers schema.
 */
class BookingRepoIT {

    private lateinit var clinicRepo: ClinicRepo
    private var clinicId: Long = 0L
    private var sessionId: Long = 0L

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        clinicRepo = ClinicRepo(PostgresTestSupport.database())
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "book").clinic.id
        sessionId = SessionRepo(PostgresTestSupport.database())
            .insertActive(clinicId, "TELEGRAM", "tg-book", "ru")
    }

    private fun columns(table: String): Map<String, String> {
        val seen = mutableMapOf<String, String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema='public' AND table_name=?",
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs -> while (rs.next()) seen[rs.getString(1)] = rs.getString(2) }
            }
        }
        return seen
    }

    private fun insertBooking(
        conn: Connection,
        externalDoctorId: String,
        slotAt: String,
        slotEnd: String,
        sourceId: String = "internal",
        status: String = "confirmed",
        externalApptId: String? = null,
        clinic: Long = clinicId,
    ) {
        conn.prepareStatement(
            "INSERT INTO bookings " +
                "(clinic_id, session_id, source_id, external_doctor_id, specialist_code, " +
                " slot_at, slot_end, external_appointment_id, status) " +
                "VALUES (?, ?, ?, ?, 'therapist', ?::timestamptz, ?::timestamptz, ?, ?)",
        ).use { ps ->
            ps.setLong(1, clinic)
            ps.setLong(2, sessionId)
            ps.setString(3, sourceId)
            ps.setString(4, externalDoctorId)
            ps.setString(5, slotAt)
            ps.setString(6, slotEnd)
            ps.setString(7, externalApptId)
            ps.setString(8, status)
            ps.executeUpdate()
        }
    }

    @Test
    fun `AC-3 bookings has the source-agnostic columns and no hl7 column`() {
        val cols = columns("bookings")
        val expected = mapOf(
            "id" to "bigint",
            "clinic_id" to "bigint",
            "session_id" to "bigint",
            "source_id" to "text",
            "external_doctor_id" to "text",
            "doctor_identity_id" to "bigint",
            "specialist_code" to "text",
            "slot_at" to "timestamp with time zone",
            "slot_end" to "timestamp with time zone",
            "external_appointment_id" to "text",
            "status" to "text",
            "feedback_score" to "smallint",
            "feedback_text" to "text",
        )
        for ((col, type) in expected) {
            val actual = cols[col] ?: fail("bookings missing column $col; saw: $cols")
            assertEquals(type, actual, "bookings.$col unexpected type")
        }
        assertTrue(cols.keys.none { it.startsWith("hl7") }, "no hl7_* column may exist; saw: ${cols.keys}")
    }

    @Test
    fun `AC-3 external_appointment_id is nullable for the internal source`() {
        PostgresTestSupport.database().getConnection().use { conn ->
            insertBooking(conn, "doc-a", "2026-07-10T09:00:00Z", "2026-07-10T09:30:00Z", externalApptId = null)
        }
        val stored = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT external_appointment_id FROM bookings WHERE external_doctor_id='doc-a'")
                .use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
        }
        assertNull(stored, "internal booking must persist with NULL external_appointment_id")
    }

    @Test
    fun `AC-4 overlapping confirmed internal bookings on the same doctor are rejected`() {
        PostgresTestSupport.database().getConnection().use { conn ->
            insertBooking(conn, "doc-a", "2026-07-10T09:00:00Z", "2026-07-10T09:30:00Z")
        }
        try {
            PostgresTestSupport.database().getConnection().use { conn ->
                insertBooking(conn, "doc-a", "2026-07-10T09:15:00Z", "2026-07-10T09:45:00Z")
            }
            fail("expected EXCLUDE constraint to reject the overlapping internal booking")
        } catch (e: Exception) {
            val msg = (e.message.orEmpty() + e.cause?.message.orEmpty()).lowercase()
            assertTrue(
                msg.contains("exclu") || msg.contains("conflict") || msg.contains("overlap"),
                "expected exclusion-constraint violation, got: ${e.message}",
            )
        }
    }

    @Test
    fun `AC-4 non-overlapping bookings and a cancelled-row overlap both insert cleanly`() {
        PostgresTestSupport.database().getConnection().use { conn ->
            insertBooking(conn, "doc-a", "2026-07-10T09:00:00Z", "2026-07-10T09:30:00Z")
            // adjacent, non-overlapping → OK
            insertBooking(conn, "doc-a", "2026-07-10T09:30:00Z", "2026-07-10T10:00:00Z")
            // overlapping but cancelled → not guarded (partial WHERE status='confirmed')
            insertBooking(conn, "doc-a", "2026-07-10T09:00:00Z", "2026-07-10T09:30:00Z", status = "cancelled")
        }
    }

    @Test
    fun `AC-4 the exclusion guard does not apply to non-internal sources`() {
        PostgresTestSupport.database().getConnection().use { conn ->
            // External mirror rows carry no double-book guarantee → overlap allowed.
            insertBooking(conn, "ext-1", "2026-07-10T09:00:00Z", "2026-07-10T09:30:00Z", sourceId = "vendorx", externalApptId = "A1")
            insertBooking(conn, "ext-1", "2026-07-10T09:15:00Z", "2026-07-10T09:45:00Z", sourceId = "vendorx", externalApptId = "A2")
        }
    }
}
