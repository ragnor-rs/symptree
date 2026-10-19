package com.symptree.core.db

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.SQLException

/**
 * Applies all `*.sql` files under [migrationsDir] in alphabetical order against
 * the database, idempotently. Already-applied migrations are tracked by filename
 * in a `schema_migrations` table the runner bootstraps on its first call.
 *
 * Each migration is applied in its own transaction. On failure, the transaction
 * rolls back and an [IllegalStateException] surfaces the offending file — startup
 * fails loud and clear instead of leaving the schema half-applied.
 *
 * For M2 this is the only DB migration mechanism; Flyway / Liquibase are
 * deferred until the migration set grows past hand-management.
 */
class MigrationRunner(
    private val database: PostgresDatabase,
    private val migrationsDir: File = File("database"),
) {
    private val logger = LoggerFactory.getLogger("com.symptree.core.db.MigrationRunner")

    fun run() {
        check(migrationsDir.isDirectory) {
            "Migrations directory '${migrationsDir.absolutePath}' does not exist or is not a directory"
        }
        val files = migrationsDir.listFiles { f -> f.isFile && f.name.endsWith(".sql") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) {
            logger.warn("No SQL migrations found in {}", migrationsDir.absolutePath)
            return
        }

        database.getConnection().use { conn ->
            ensureTrackingTable(conn)
            val applied = loadAppliedNames(conn)
            for (file in files) {
                if (file.name in applied) {
                    logger.debug("Skipping already-applied migration {}", file.name)
                    continue
                }
                applyMigration(conn, file)
            }
        }
    }

    private fun ensureTrackingTable(conn: java.sql.Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    name        TEXT PRIMARY KEY,
                    applied_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """.trimIndent(),
            )
        }
    }

    private fun loadAppliedNames(conn: java.sql.Connection): Set<String> {
        val names = mutableSetOf<String>()
        conn.prepareStatement("SELECT name FROM schema_migrations").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) names.add(rs.getString(1))
            }
        }
        return names
    }

    private fun applyMigration(conn: java.sql.Connection, file: File) {
        logger.info("Applying migration {}", file.name)
        val sql = file.readText()
        val priorAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.createStatement().use { stmt -> stmt.execute(sql) }
            conn.prepareStatement("INSERT INTO schema_migrations (name) VALUES (?)").use { ps ->
                ps.setString(1, file.name)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: SQLException) {
            conn.rollback()
            throw IllegalStateException("Migration ${file.name} failed: ${e.message}", e)
        } finally {
            conn.autoCommit = priorAutoCommit
        }
    }
}
