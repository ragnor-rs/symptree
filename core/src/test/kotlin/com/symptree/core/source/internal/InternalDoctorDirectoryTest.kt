package com.symptree.core.source.internal

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewDoctor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-YDANPW.6 + .8 — InternalDoctorDirectory over DoctorRepo (Testcontainers).
 *
 *  - `listDoctors(clinicId)` returns only active, non-deleted doctors for the clinic,
 *  - each `DoctorRef` carries the union of its identity's specialist codes
 *    (free-form strings matched against `ConclusionNode.specialist` — no new enum).
 */
class InternalDoctorDirectoryTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var doctorRepo: DoctorRepo
    private lateinit var directory: InternalDoctorDirectory
    private val sourceId = "internal"

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        clinicRepo = ClinicRepo(PostgresTestSupport.database())
        doctorRepo = DoctorRepo(PostgresTestSupport.database())
        directory = InternalDoctorDirectory(doctorRepo)
    }

    private fun newDoctor(
        clinicId: Long,
        externalId: String,
        fullName: String,
        specialistCodes: List<String>,
        active: Boolean = true,
    ) = NewDoctor(
        clinicId = clinicId,
        sourceId = sourceId,
        externalId = externalId,
        fullName = fullName,
        specialistCodes = specialistCodes,
        slotMinutes = 30,
        active = active,
    )

    @Test
    fun `AC-8 listDoctors returns active non-deleted doctors for the clinic`() = runBlocking {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "dir-a").clinic
        doctorRepo.insert(newDoctor(clinic.id, "d-active", "Dr Active", listOf("therapist")))
        val inactive = doctorRepo.insert(newDoctor(clinic.id, "d-inactive", "Dr Inactive", listOf("therapist"), active = false))
        val deleted = doctorRepo.insert(newDoctor(clinic.id, "d-deleted", "Dr Deleted", listOf("therapist")))
        doctorRepo.softDelete(clinic.id, deleted.id)

        val refs = directory.listDoctors(clinic.id)

        assertEquals(setOf("d-active"), refs.map { it.externalId }.toSet(),
            "only active, non-deleted doctors should be listed")
        assertTrue(refs.none { it.externalId == inactive.externalId })
    }

    @Test
    fun `AC-8 listDoctors is clinic-scoped - does not leak doctors from another clinic`() = runBlocking {
        val a = Fixtures.fixtureClinic(clinicRepo, slug = "dir-iso-a").clinic
        val b = Fixtures.fixtureClinic(clinicRepo, slug = "dir-iso-b").clinic
        doctorRepo.insert(newDoctor(a.id, "a1", "A One", listOf("therapist")))
        doctorRepo.insert(newDoctor(b.id, "b1", "B One", listOf("cardiologist")))

        val refsA = directory.listDoctors(a.id)

        assertEquals(setOf("a1"), refsA.map { it.externalId }.toSet(),
            "clinic A directory must not surface clinic B's doctors")
    }

    @Test
    fun `AC-6 DoctorRef carries the union of its identity's specialist codes`() = runBlocking {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "dir-spec").clinic
        doctorRepo.insert(newDoctor(clinic.id, "multi", "Dr Multi", listOf("therapist", "cardiologist")))

        val ref = directory.listDoctors(clinic.id).single()

        assertEquals(setOf("therapist", "cardiologist"), ref.specialistCodes.toSet(),
            "DoctorRef.specialistCodes must be the free-form code union (matched vs ConclusionNode.specialist)")
    }
}
