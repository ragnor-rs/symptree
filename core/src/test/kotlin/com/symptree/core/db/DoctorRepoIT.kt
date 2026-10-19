package com.symptree.core.db

import com.symptree.core.Fixtures
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewDoctor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-YDANPW.4, .7, .8 — canonical doctor catalog schema + DoctorRepo (Testcontainers).
 *
 * Migrations `05-migrate-doctors.sql` (doctor_identity + doctors) and
 * `06-migrate-doctor-schedule.sql` apply cleanly on top of 01–04.
 */
class DoctorRepoIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var repo: DoctorRepo
    private val sourceId = "internal"

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        clinicRepo = ClinicRepo(PostgresTestSupport.database())
        repo = DoctorRepo(PostgresTestSupport.database())
    }

    private fun newDoctor(
        clinicId: Long,
        externalId: String,
        fullName: String = "Dr $externalId",
        specialistCodes: List<String> = listOf("therapist"),
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

    // ---------------- AC-YDANPW.4 — schema ----------------

    @Test
    fun `AC-4 doctor_identity table has required columns`() {
        val cols = columns("doctor_identity")
        val expected = mapOf(
            "id" to "bigint",
            "clinic_id" to "bigint",
            "display_name" to "text",
            "created_at" to "timestamp with time zone",
            "updated_at" to "timestamp with time zone",
        )
        for ((col, type) in expected) {
            val actual = cols[col] ?: fail("doctor_identity missing column $col; saw: $cols")
            assertEquals(type, actual, "doctor_identity.$col unexpected type")
        }
    }

    @Test
    fun `AC-4 doctors table has the canonical catalog columns with expected types`() {
        val cols = columns("doctors")
        val expected = mapOf(
            "id" to "bigint",
            "clinic_id" to "bigint",
            "identity_id" to "bigint",
            "source_id" to "text",
            "external_id" to "text",
            "specialist_codes" to "ARRAY",
            "slot_minutes" to "integer",
            "full_name" to "text",
            "active" to "boolean",
            "deleted_at" to "timestamp with time zone",
            "synced_at" to "timestamp with time zone",
            "sync_cursor" to "text",
            "created_at" to "timestamp with time zone",
        )
        for ((col, type) in expected) {
            val actual = cols[col] ?: fail("doctors missing column $col; saw: $cols")
            assertEquals(type, actual, "doctors.$col unexpected type")
        }
    }

    @Test
    fun `AC-4 doctors enforces UNIQUE on source_id+external_id`() {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "uniq").clinic
        repo.insert(newDoctor(clinic.id, "dup"))
        try {
            repo.insert(newDoctor(clinic.id, "dup"))
            fail("expected (source_id, external_id) uniqueness violation")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("unique", ignoreCase = true) == true ||
                    e.message?.contains("duplicate", ignoreCase = true) == true ||
                    e.cause?.message?.contains("unique", ignoreCase = true) == true,
                "expected unique-violation error, got: ${e.message}",
            )
        }
    }

    @Test
    fun `AC-4 doctors and doctor_identity carry clinic_id and reference clinics`() {
        // FK from doctors.clinic_id to clinics(id): inserting against a missing clinic must fail.
        try {
            repo.insert(newDoctor(clinicId = 999_999L, externalId = "orphan"))
            fail("expected FK violation inserting doctor for non-existent clinic")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("foreign key", ignoreCase = true) == true ||
                    e.message?.contains("violates", ignoreCase = true) == true ||
                    e.cause?.message?.contains("foreign key", ignoreCase = true) == true,
                "expected FK-violation error, got: ${e.message}",
            )
        }
    }

    // ---------------- AC-YDANPW.7 — identity auto-creation ----------------

    @Test
    fun `AC-7 inserting a doctor auto-creates exactly one singleton identity`() {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "id-auto").clinic
        val inserted = repo.insert(newDoctor(clinic.id, "d1"))

        assertTrue(inserted.identityId > 0, "inserted doctor must carry a non-zero identity_id")

        val identityCount = scalarLong(
            "SELECT COUNT(*) FROM doctor_identity WHERE clinic_id=${clinic.id}")
        assertEquals(1L, identityCount, "exactly one doctor_identity row should exist for one doctor")
    }

    @Test
    fun `AC-7 upsert keyed on source_id+external_id preserves identity_id`() {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "id-keep").clinic
        val first = repo.insert(newDoctor(clinic.id, "stable", fullName = "Old Name"))
        val originalIdentity = first.identityId

        val reupserted = repo.upsert(newDoctor(clinic.id, "stable", fullName = "New Name"))

        assertEquals(originalIdentity, reupserted.identityId,
            "re-upsert on (source_id, external_id) must not clear/reassign identity_id")
        assertEquals("New Name", reupserted.fullName, "upsert should refresh mutable fields")

        val identityCount = scalarLong(
            "SELECT COUNT(*) FROM doctor_identity WHERE clinic_id=${clinic.id}")
        assertEquals(1L, identityCount, "re-upsert must not spawn a second identity")
    }

    @Test
    fun `AC-7 concurrent upserts of the same key yield exactly one identity (no orphan)`() {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "race").clinic
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val startGate = java.util.concurrent.CountDownLatch(1)
        try {
            val futures = (1..2).map { n ->
                pool.submit {
                    startGate.await()
                    repo.upsert(newDoctor(clinic.id, "shared", fullName = "Race $n"))
                }
            }
            startGate.countDown()
            futures.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // Per-key advisory lock serializes the two upserts: the second sees the
        // first's row and reuses its identity, so no identity is left orphaned.
        val doctorCount = scalarLong(
            "SELECT COUNT(*) FROM doctors WHERE clinic_id=${clinic.id} AND external_id='shared'")
        val identityCount = scalarLong(
            "SELECT COUNT(*) FROM doctor_identity WHERE clinic_id=${clinic.id}")
        assertEquals(1L, doctorCount, "exactly one doctor row for the shared key")
        assertEquals(1L, identityCount, "concurrent upsert must not orphan a doctor_identity row")
    }

    // ---------------- AC-YDANPW.8 — repo CRUD / lookup / filter / soft-delete ----------------

    @Test
    fun `AC-8 findById round-trips an inserted doctor`() {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "crud").clinic
        val inserted = repo.insert(newDoctor(clinic.id, "rt", fullName = "Round Trip", specialistCodes = listOf("therapist", "ent")))
        val got = repo.findById(clinic.id, inserted.id) ?: fail("doctor vanished")
        assertEquals("rt", got.externalId)
        assertEquals("Round Trip", got.fullName)
        assertEquals(setOf("therapist", "ent"), got.specialistCodes.toSet())
    }

    @Test
    fun `AC-8 listActive excludes inactive and soft-deleted rows`() {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "active").clinic
        repo.insert(newDoctor(clinic.id, "a"))
        repo.insert(newDoctor(clinic.id, "inactive", active = false))
        val del = repo.insert(newDoctor(clinic.id, "deleted"))
        repo.softDelete(clinic.id, del.id)

        val active = repo.listActive(clinic.id)
        assertEquals(setOf("a"), active.map { it.externalId }.toSet())
    }

    @Test
    fun `AC-8 listBySpecialist filters on specialist_codes membership`() {
        val clinic = Fixtures.fixtureClinic(clinicRepo, slug = "filter").clinic
        repo.insert(newDoctor(clinic.id, "card", specialistCodes = listOf("cardiologist")))
        repo.insert(newDoctor(clinic.id, "ther", specialistCodes = listOf("therapist")))
        repo.insert(newDoctor(clinic.id, "both", specialistCodes = listOf("therapist", "cardiologist")))

        val cardiologists = repo.listBySpecialist(clinic.id, "cardiologist")
        assertEquals(setOf("card", "both"), cardiologists.map { it.externalId }.toSet(),
            "specialist filter must match any doctor whose codes contain the requested code")
    }

    @Test
    fun `AC-8 findById is clinic-scoped - cross-clinic lookup returns null`() {
        val a = Fixtures.fixtureClinic(clinicRepo, slug = "x-a").clinic
        val b = Fixtures.fixtureClinic(clinicRepo, slug = "x-b").clinic
        val docA = repo.insert(newDoctor(a.id, "secret"))

        assertNotNull(repo.findById(a.id, docA.id))
        assertNull(repo.findById(b.id, docA.id),
            "a doctor of clinic A must not be reachable scoped to clinic B")
    }

    @Test
    fun `AC-8 listBySpecialist is clinic-scoped`() {
        val a = Fixtures.fixtureClinic(clinicRepo, slug = "fs-a").clinic
        val b = Fixtures.fixtureClinic(clinicRepo, slug = "fs-b").clinic
        repo.insert(newDoctor(a.id, "a-card", specialistCodes = listOf("cardiologist")))
        repo.insert(newDoctor(b.id, "b-card", specialistCodes = listOf("cardiologist")))

        val forA = repo.listBySpecialist(a.id, "cardiologist")
        assertEquals(setOf("a-card"), forA.map { it.externalId }.toSet(),
            "specialist lookup must not cross the clinic boundary")
    }

    // ---------------- helpers ----------------

    private fun scalarLong(sql: String): Long {
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(sql).executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }
}
