package com.symptree.core.db

import com.symptree.core.Fixtures
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewDoctor
import com.symptree.core.source.DoctorRef
import com.symptree.core.source.SourceId
import com.symptree.core.source.Slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * AC-W03362.5 — `pending_bookings` (migration 10) holds the per-session proposed
 * slot + decline count between the auto-assign proposal and the patient's
 * confirm/decline. One row per session (UNIQUE session_id); a re-propose upserts.
 * Migration 11 adds `declined_slots` (every declined `slot_at`, FR-W03362 AC.7).
 */
class PendingBookingRepoIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var doctorRepo: DoctorRepo
    private lateinit var sessionRepo: SessionRepo
    private lateinit var repo: PendingBookingRepo
    private var clinicId: Long = 0L
    private var sessionId: Long = 0L
    private var identityId: Long = 0L

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        doctorRepo = DoctorRepo(db)
        sessionRepo = SessionRepo(db)
        repo = PendingBookingRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "pb").clinic.id
        sessionId = sessionRepo.insertActive(clinicId, "TELEGRAM", "tg-pb", "ru")
        identityId = doctorRepo.insert(
            NewDoctor(clinicId, "internal", "pb-doc", "Dr Pending", listOf("therapist"), 30, true),
        ).identityId
    }

    private fun slot(start: String, end: String) = Slot(
        DoctorRef(SourceId("internal"), "pb-doc", identityId, "Dr Pending", listOf("therapist")),
        Instant.parse(start),
        Instant.parse(end),
    )

    private fun columns(table: String): Set<String> =
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema='public' AND table_name=?",
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs ->
                    val out = mutableSetOf<String>(); while (rs.next()) out += rs.getString(1); out
                }
            }
        }

    @Test
    fun `pending_bookings has the required columns`() {
        val cols = columns("pending_bookings")
        for (c in listOf(
            "id", "clinic_id", "session_id", "specialist_code", "source_id",
            "external_doctor_id", "doctor_identity_id", "doctor_display_name",
            "slot_at", "slot_end", "decline_count",
        )) {
            if (c !in cols) fail("pending_bookings missing column $c; saw $cols")
        }
    }

    @Test
    fun `propose then find round-trips the slot`() {
        repo.propose(clinicId, sessionId, slot("2026-07-07T10:00:00Z", "2026-07-07T10:30:00Z"), "therapist")
        val found = repo.find(clinicId, sessionId)
        assertNotNull(found)
        assertEquals("pb-doc", found.externalDoctorId)
        assertEquals(Instant.parse("2026-07-07T10:00:00Z"), found.slotAt)
        assertEquals("therapist", found.specialistCode)
        assertEquals(0, found.declineCount)
        assertEquals("therapist", found.toSlot().doctorRef.specialistCodes.single())
    }

    @Test
    fun `re-propose upserts the same row preserving decline count`() {
        repo.propose(clinicId, sessionId, slot("2026-07-07T10:00:00Z", "2026-07-07T10:30:00Z"), "therapist")
        assertEquals(1, repo.incrementDecline(clinicId, sessionId).declineCount)
        repo.propose(clinicId, sessionId, slot("2026-07-07T10:30:00Z", "2026-07-07T11:00:00Z"), "therapist")
        val found = repo.find(clinicId, sessionId)!!
        assertEquals(Instant.parse("2026-07-07T10:30:00Z"), found.slotAt, "slot updated on re-propose")
        assertEquals(1, found.declineCount, "decline count preserved across re-propose")
        assertEquals(
            listOf(Instant.parse("2026-07-07T10:00:00Z")), found.declinedSlots,
            "the declined slot is recorded and preserved across re-propose (FR-W03362 AC.7)",
        )
        // still exactly one row for the session
        repo.propose(clinicId, sessionId, slot("2026-07-07T11:00:00Z", "2026-07-07T11:30:00Z"), "therapist")
        assertNotNull(repo.find(clinicId, sessionId))
    }

    @Test
    fun `incrementDecline returns the running count and delete clears the row`() {
        repo.propose(clinicId, sessionId, slot("2026-07-07T10:00:00Z", "2026-07-07T10:30:00Z"), "therapist")
        assertEquals(1, repo.incrementDecline(clinicId, sessionId).declineCount)
        assertEquals(2, repo.incrementDecline(clinicId, sessionId).declineCount)
        repo.delete(clinicId, sessionId)
        assertNull(repo.find(clinicId, sessionId))
    }

    @Test
    fun `incrementDecline accumulates each declined slot_at across re-proposals`() {
        // Realistic cycle: propose 10:00 → decline → re-propose 10:30 → decline.
        // Both declined slot_ats accumulate so the re-propose can exclude all of them.
        repo.propose(clinicId, sessionId, slot("2026-07-07T10:00:00Z", "2026-07-07T10:30:00Z"), "therapist")
        repo.incrementDecline(clinicId, sessionId) // records 10:00
        repo.propose(clinicId, sessionId, slot("2026-07-07T10:30:00Z", "2026-07-07T11:00:00Z"), "therapist")
        val afterSecond = repo.incrementDecline(clinicId, sessionId) // records 10:30
        assertEquals(2, afterSecond.declineCount)
        assertEquals(
            listOf(Instant.parse("2026-07-07T10:00:00Z"), Instant.parse("2026-07-07T10:30:00Z")),
            afterSecond.declinedSlots,
            "every declined slot_at is accumulated, not just the latest",
        )
    }
}
