package com.symptree.core.db

import com.symptree.core.Fixtures
import com.symptree.core.auth.ApiKeyHasher
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewClinicUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-MG7TSR.1 — `clinic_users` migration + repo.
 *
 * Verifies the schema (columns + `UNIQUE (clinic_id, email)`), CRUD round-trip,
 * cross-clinic isolation, and that the password is only ever persisted as a
 * bcrypt hash (never plaintext, in DB or in a returned model).
 */
class ClinicUserRepoIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var repo: ClinicUserRepo
    private var clinicAId: Long = 0L
    private var clinicBId: Long = 0L

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        repo = ClinicUserRepo(db)
        clinicAId = Fixtures.fixtureClinic(clinicRepo, slug = "cu-a").clinic.id
        clinicBId = Fixtures.fixtureClinic(clinicRepo, slug = "cu-b").clinic.id
    }

    // ---------------- schema ----------------

    @Test
    fun `AC-1 clinic_users table has every required column with the expected type`() {
        val expected = mapOf(
            "id" to "bigint",
            "clinic_id" to "bigint",
            "email" to "text",
            "password_hash" to "text",
            "display_name" to "text",
            "role" to "text",
            "active" to "boolean",
            "created_at" to "timestamp with time zone",
            "updated_at" to "timestamp with time zone",
        )
        val seen = mutableMapOf<String, String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = 'clinic_users'",
            ).executeQuery().use { rs ->
                while (rs.next()) seen[rs.getString(1)] = rs.getString(2)
            }
        }
        for ((col, type) in expected) {
            val actual = seen[col] ?: fail("clinic_users missing column $col; saw: $seen")
            assertEquals(type, actual, "column $col has unexpected type")
        }
    }

    @Test
    fun `AC-1 email is unique per clinic but the same email may exist in two clinics`() {
        val hash = ApiKeyHasher.hash("pw")
        repo.insert(NewClinicUser(clinicAId, "dr@x.io", hash, "Dr A", "admin", true))
        // Same email, same clinic -> uniqueness violation.
        try {
            repo.insert(NewClinicUser(clinicAId, "dr@x.io", hash, "Dr A dup", "admin", true))
            fail("expected UNIQUE (clinic_id, email) violation")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains("unique", ignoreCase = true) == true ||
                    e.message?.contains("dup", ignoreCase = true) == true ||
                    e.cause?.message?.contains("unique", ignoreCase = true) == true,
                "expected unique-violation error, got: ${e.message}",
            )
        }
        // Same email in a DIFFERENT clinic must be allowed.
        val other = repo.insert(NewClinicUser(clinicBId, "dr@x.io", hash, "Dr B", "operator", true))
        assertTrue(other.id > 0, "same email in a different clinic must insert")
    }

    // ---------------- behavior ----------------

    @Test
    fun `insert then findByClinicAndEmail round-trips`() {
        val hash = ApiKeyHasher.hash("s3cret")
        val inserted = repo.insert(NewClinicUser(clinicAId, "op@x.io", hash, "Operator", "operator", true))
        assertTrue(inserted.id > 0)
        assertEquals(clinicAId, inserted.clinicId)
        assertEquals("op@x.io", inserted.email)
        assertEquals("operator", inserted.role)

        val found = repo.findByClinicAndEmail(clinicAId, "op@x.io")
        assertNotNull(found)
        assertEquals(inserted.id, found.id)
    }

    @Test
    fun `findByClinicAndEmail isolates by clinic - clinic A user is invisible to clinic B`() {
        val hash = ApiKeyHasher.hash("pw")
        repo.insert(NewClinicUser(clinicAId, "only-a@x.io", hash, "A only", "admin", true))
        assertNotNull(repo.findByClinicAndEmail(clinicAId, "only-a@x.io"))
        assertNull(
            repo.findByClinicAndEmail(clinicBId, "only-a@x.io"),
            "a clinic-A user must not resolve under clinic B's id",
        )
    }

    @Test
    fun `findByClinicAndEmail returns null for unknown email`() {
        assertNull(repo.findByClinicAndEmail(clinicAId, "nobody@x.io"))
    }

    @Test
    fun `password is stored only as a bcrypt hash, never plaintext`() {
        val plaintext = "super-secret-password"
        val hash = ApiKeyHasher.hash(plaintext)
        repo.insert(NewClinicUser(clinicAId, "secure@x.io", hash, "Secure", "admin", true))

        // The persisted hash must verify against the original but must not equal it.
        val stored = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT password_hash FROM clinic_users WHERE clinic_id = ? AND email = ?",
            ).use { ps ->
                ps.setLong(1, clinicAId)
                ps.setString(2, "secure@x.io")
                ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }
        assertTrue(stored != plaintext, "password_hash must not equal the plaintext password")
        assertTrue(!stored.contains(plaintext), "password_hash must not contain the plaintext")
        assertTrue(ApiKeyHasher.verify(plaintext, stored), "stored hash must verify against the original password")

        // The returned model must not expose the plaintext either.
        val found = repo.findByClinicAndEmail(clinicAId, "secure@x.io") ?: fail("user vanished")
        assertTrue(found.passwordHash != plaintext, "returned model must not carry plaintext password")
    }
}
