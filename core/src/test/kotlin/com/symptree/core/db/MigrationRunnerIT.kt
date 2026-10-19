package com.symptree.core.db

import com.symptree.core.integration.PostgresTestSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationRunnerIT {

    @BeforeEach
    fun setUp() {
        // Drop and recreate the public schema so schema_migrations is also gone.
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP SCHEMA public CASCADE")
                stmt.execute("CREATE SCHEMA public")
            }
        }
    }

    @Test
    fun `runs every sql file in alphabetical order against an empty database`(@TempDir tmp: Path) {
        write(tmp, "01-create-a.sql", "CREATE TABLE a (id BIGSERIAL PRIMARY KEY);")
        write(tmp, "02-create-b.sql", "CREATE TABLE b (id BIGSERIAL PRIMARY KEY);")

        MigrationRunner(PostgresTestSupport.database(), tmp.toFile()).run()

        assertTrue(tableExists("a"), "table 'a' must be created by 01-create-a.sql")
        assertTrue(tableExists("b"), "table 'b' must be created by 02-create-b.sql")
        assertEquals(setOf("01-create-a.sql", "02-create-b.sql"), appliedNames())
    }

    @Test
    fun `second run is a no-op when migrations are already applied`(@TempDir tmp: Path) {
        write(tmp, "01-create-c.sql", "CREATE TABLE c (id BIGSERIAL PRIMARY KEY);")

        MigrationRunner(PostgresTestSupport.database(), tmp.toFile()).run()
        // A second run must not throw despite CREATE TABLE being non-idempotent — the
        // runner consults schema_migrations and skips already-applied files.
        MigrationRunner(PostgresTestSupport.database(), tmp.toFile()).run()

        assertEquals(setOf("01-create-c.sql"), appliedNames())
    }

    @Test
    fun `new migration is picked up on a subsequent run alongside the existing tracked ones`(@TempDir tmp: Path) {
        write(tmp, "01-first.sql", "CREATE TABLE first (id BIGSERIAL PRIMARY KEY);")
        MigrationRunner(PostgresTestSupport.database(), tmp.toFile()).run()

        write(tmp, "02-second.sql", "CREATE TABLE second (id BIGSERIAL PRIMARY KEY);")
        MigrationRunner(PostgresTestSupport.database(), tmp.toFile()).run()

        assertTrue(tableExists("first"))
        assertTrue(tableExists("second"))
        assertEquals(setOf("01-first.sql", "02-second.sql"), appliedNames())
    }

    @Test
    fun `failed migration rolls back and surfaces a clear error naming the file`(@TempDir tmp: Path) {
        write(tmp, "01-good.sql", "CREATE TABLE good (id BIGSERIAL PRIMARY KEY);")
        write(tmp, "02-broken.sql", "CREATE TABLE broken (this is not valid SQL);")

        val err = assertFailsWith<IllegalStateException> {
            MigrationRunner(PostgresTestSupport.database(), tmp.toFile()).run()
        }
        assertTrue(
            "02-broken.sql" in err.message.orEmpty(),
            "error must name the failing file; got: ${err.message}",
        )
        // Tracking row for the broken migration must NOT have been recorded.
        assertEquals(setOf("01-good.sql"), appliedNames())
        assertTrue(tableExists("good"), "01-good.sql must remain applied")
    }

    @Test
    fun `no-op run with zero migrations does not even bootstrap the tracking table`(@TempDir tmp: Path) {
        MigrationRunner(PostgresTestSupport.database(), tmp.toFile()).run()
        assertTrue(
            tableExists("schema_migrations").not(),
            "no-op run with zero migrations must not create schema_migrations either",
        )
    }

    @Test
    fun `tracking table is bootstrapped on the first real run`(@TempDir tmp: Path) {
        write(tmp, "01-x.sql", "CREATE TABLE x (id BIGSERIAL PRIMARY KEY);")
        MigrationRunner(PostgresTestSupport.database(), tmp.toFile()).run()
        assertTrue(tableExists("schema_migrations"),
            "schema_migrations must exist after the first migration is applied")
    }

    private fun write(dir: Path, name: String, sql: String) {
        File(dir.toFile(), name).writeText(sql)
    }

    private fun tableExists(name: String): Boolean {
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables " +
                    "WHERE table_schema='public' AND table_name=?",
            ).use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    private fun appliedNames(): Set<String> {
        val names = mutableSetOf<String>()
        PostgresTestSupport.database().getConnection().use { conn ->
            // schema_migrations may not exist yet on an empty-run scenario.
            val exists = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables " +
                    "WHERE table_schema='public' AND table_name='schema_migrations'",
            ).use { ps -> ps.executeQuery().use { rs -> rs.next() } }
            if (!exists) return emptySet()
            conn.prepareStatement("SELECT name FROM schema_migrations").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) names.add(rs.getString(1))
                }
            }
        }
        return names
    }
}
