package com.symptree.core.booking

import com.symptree.core.source.DoctorRef
import com.symptree.core.source.Slot
import com.symptree.core.source.SourceId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * AC-W03362.2 — `FirstAvailable` picks the earliest remaining slot across all
 * candidate doctors; ties (equal earliest start) break toward the doctor with
 * the most free capacity in the window.
 */
class SlotStrategyTest {

    private fun doctor(id: String) = DoctorRef(
        sourceId = SourceId("internal"),
        externalId = id,
        identityId = id.hashCode().toLong(),
        displayName = "Dr $id",
        specialistCodes = listOf("therapist"),
    )

    private fun slot(doc: DoctorRef, start: String) =
        Slot(doc, Instant.parse(start), Instant.parse(start).plusSeconds(1800))

    @Test
    fun `FirstAvailable picks the globally earliest slot across doctors`() {
        val a = doctor("a")
        val b = doctor("b")
        val perDoctor = mapOf(
            a to listOf(slot(a, "2026-07-07T11:00:00Z"), slot(a, "2026-07-07T11:30:00Z")),
            b to listOf(slot(b, "2026-07-07T09:00:00Z"), slot(b, "2026-07-07T09:30:00Z")),
        )
        val picked = FirstAvailable.pick(perDoctor)
        assertEquals(Instant.parse("2026-07-07T09:00:00Z"), picked?.start)
        assertEquals("b", picked?.doctorRef?.externalId, "doctor b has the earliest slot")
    }

    @Test
    fun `FirstAvailable tie-breaks by free capacity when earliest starts are equal`() {
        val a = doctor("a")
        val b = doctor("b")
        val perDoctor = mapOf(
            // both earliest at 09:00; b has more free slots in the window
            a to listOf(slot(a, "2026-07-07T09:00:00Z")),
            b to listOf(slot(b, "2026-07-07T09:00:00Z"), slot(b, "2026-07-07T09:30:00Z"), slot(b, "2026-07-07T10:00:00Z")),
        )
        val picked = FirstAvailable.pick(perDoctor)
        assertEquals(Instant.parse("2026-07-07T09:00:00Z"), picked?.start)
        assertEquals("b", picked?.doctorRef?.externalId, "tie at 09:00 → most-free-capacity doctor (b) wins")
    }

    @Test
    fun `FirstAvailable returns null when no doctor has any slot`() {
        val a = doctor("a")
        assertEquals(null, FirstAvailable.pick(mapOf(a to emptyList())), "no slots anywhere → null")
    }
}
