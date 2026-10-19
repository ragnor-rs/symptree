package com.symptree.core.source

import com.symptree.core.source.internal.InternalDoctorDirectory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * AC-YDANPW.1 / .2 / .3 — pure (no-DB) tests over the source abstraction.
 *
 * M2 scope is a registry holding exactly one INTERNAL source. The composition +
 * single-source contract is exercised with in-memory fake providers:
 *  - the lone INTERNAL source's doctors are returned by `listAllDoctors`,
 *  - the booking source-router resolves a `DoctorRef` to its owning source's
 *    `booking` provider (and returns null when that source has no binding),
 *  - `BookingResult` exposes `externalRef: String?` (NOT `hl7AppointmentId`),
 *  - the capability interfaces are segregated.
 *
 * Multi-source federation (≥2 sources of differing kinds, cross-source booking
 * routing, directory-less sources contributing zero) is deferred to M3 with the
 * second source's adapter; its scenarios are tracked in specs/plan/M3.md.
 */
class DataSourceRegistryTest {

    private val internalSource = SourceId("internal")

    private fun doctorRef(
        externalId: String,
        identityId: Long,
        specialistCodes: List<String> = listOf("therapist"),
        source: SourceId = internalSource,
    ) = DoctorRef(
        sourceId = source,
        externalId = externalId,
        identityId = identityId,
        displayName = "Dr $externalId",
        specialistCodes = specialistCodes,
    )

    private class FakeDirectory(private val doctors: List<DoctorRef>) : DoctorDirectory {
        override suspend fun listDoctors(clinicId: Long): List<DoctorRef> = doctors
    }

    private class FakeBooking(val label: String) : BookingProvider {
        var lastBooked: Slot? = null
        override suspend fun book(slot: Slot, patient: PatientRef): BookingResult {
            lastBooked = slot
            return BookingResult(externalRef = null, confirmedSlot = slot)
        }
        override suspend fun cancel(ref: ExternalApptRef) { /* no-op */ }
    }

    // ---------------- AC-YDANPW.2 — single INTERNAL source ----------------

    @Test
    fun `AC-2 registry with one INTERNAL source returns its doctors via listAllDoctors`() = runBlocking {
        val refs = listOf(doctorRef("d1", 1L), doctorRef("d2", 2L))
        val source = MedicalDataSource(
            id = internalSource,
            kind = SourceKind.INTERNAL,
            directory = FakeDirectory(refs),
            scheduling = null,
            booking = FakeBooking("internal"),
        )
        val registry = DataSourceRegistry(listOf(source))

        val all = registry.listAllDoctors(clinicId = 42L)

        assertEquals(listOf("d1", "d2"), all.map { it.externalId })
    }

    @Test
    fun `AC-2 MVP registry holds exactly one INTERNAL source`() {
        val source = MedicalDataSource(
            id = internalSource,
            kind = SourceKind.INTERNAL,
            directory = FakeDirectory(emptyList()),
            scheduling = null,
            booking = null,
        )
        val registry = DataSourceRegistry(listOf(source))
        assertEquals(1, registry.sources.size)
        assertEquals(SourceKind.INTERNAL, registry.sources.single().kind)
    }

    // ---------------- AC-YDANPW.2 — booking source-routing ----------------

    @Test
    fun `AC-2 booking route resolves a DoctorRef to its owning source's booking provider`() = runBlocking {
        val internalBooking = FakeBooking("internal")
        val internal = MedicalDataSource(
            id = internalSource,
            kind = SourceKind.INTERNAL,
            directory = FakeDirectory(listOf(doctorRef("d1", 1L))),
            scheduling = null,
            booking = internalBooking,
        )
        val registry = DataSourceRegistry(listOf(internal))

        val resolved = registry.bookingFor(doctorRef("d1", 1L))

        assertSame(internalBooking, resolved,
            "bookingFor must route to the booking provider of the doctor's owning source")
    }

    @Test
    fun `AC-2 booking route returns null when owning source has no booking binding`() {
        val internal = MedicalDataSource(
            id = internalSource,
            kind = SourceKind.INTERNAL,
            directory = FakeDirectory(emptyList()),
            scheduling = null,
            booking = null,
        )
        val registry = DataSourceRegistry(listOf(internal))
        assertNull(registry.bookingFor(doctorRef("d1", 1L)),
            "no booking binding ⇒ no routable provider")
    }

    // ---------------- AC-YDANPW.3 — BookingResult shape ----------------

    @Test
    fun `AC-3 BookingResult carries externalRef and confirmedSlot, null for internal`() = runBlocking {
        val ref = doctorRef("d1", 1L)
        val slot = Slot(doctorRef = ref, start = Instant.parse("2026-07-01T09:00:00Z"),
            end = Instant.parse("2026-07-01T09:30:00Z"))
        val booking = FakeBooking("internal")
        val patient = PatientRef(externalId = null, displayName = "Pat", phone = "+100", languageCode = "ru")

        val result: BookingResult = booking.book(slot, patient)

        assertNull(result.externalRef, "internal booking has null externalRef")
        assertEquals(slot, result.confirmedSlot)
    }

    @Test
    fun `AC-3 BookingResult has externalRef property and no hl7AppointmentId`() {
        val propNames = BookingResult::class.members.map { it.name }.toSet()
        assertTrue("externalRef" in propNames, "BookingResult must expose externalRef; saw $propNames")
        assertTrue(propNames.none { it.equals("hl7AppointmentId", ignoreCase = true) },
            "BookingResult must NOT expose hl7AppointmentId; saw $propNames")
    }

    // ---------------- AC-YDANPW.1 — interface segregation (thin interfaces) ----------------

    @Test
    fun `AC-1 capability interfaces are segregated - InternalDoctorDirectory only implements DoctorDirectory`() {
        val interfaces = InternalDoctorDirectory::class.supertypes.mapNotNull {
            (it.classifier as? kotlin.reflect.KClass<*>)?.simpleName
        }
        assertTrue("DoctorDirectory" in interfaces,
            "InternalDoctorDirectory must implement DoctorDirectory; saw $interfaces")
        assertTrue("SchedulingProvider" !in interfaces && "BookingProvider" !in interfaces,
            "DoctorDirectory impl must NOT be forced to implement scheduling/booking; saw $interfaces")
    }
}
