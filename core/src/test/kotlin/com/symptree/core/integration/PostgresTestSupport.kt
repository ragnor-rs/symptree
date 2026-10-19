package com.symptree.core.integration

import com.symptree.core.config.CoreConfig
import com.symptree.core.db.PostgresDatabase
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.DriverManager

/**
 * Shared Testcontainers Postgres 16 support for integration tests in this module.
 *
 * Starts one container per JVM (lazy singleton); each test class calls [freshSchema]
 * in @BeforeEach to drop & re-apply migrations so tests stay isolated.
 *
 * Migrations applied: every `database/NN-migrate-*.sql` found at the repo root,
 * in lexical order. The minimum surface for AC-M3WYNS.* is `01-migrate-clinics.sql`.
 */
object PostgresTestSupport {

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("symptree_test")
            .withUsername("symptree_test")
            .withPassword("symptree_test")
            .also { it.start() }
    }

    fun backendConfig(): CoreConfig {
        val c = container
        return CoreConfig(
            port = 0,
            postgresHost = c.host,
            postgresPort = c.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            postgresDb = c.databaseName,
            postgresUser = c.username,
            postgresPassword = c.password,
            platformAdminApiKey = "pak_postgres_test_support",
            botIngressSharedSecret = "bot_postgres_test_support",
            botInternalBaseUrl = "http://localhost:8084",
            botInternalSharedSecret = "bot_internal_postgres_test_support",
            openAiApiKey = "sk-test-postgres-test-support",
            openAiModel = CoreConfig.DEFAULT_OPENAI_MODEL,
            piiKey = "0123456789abcdef0123456789abcdef",
            adminTokenSecret = "admin-token-postgres-test-support",
            adminTokenTtlMinutes = 480L,
        )
    }

    fun database(): PostgresDatabase = PostgresDatabase(backendConfig())

    fun jdbcUrl(): String {
        val c = container
        return "jdbc:postgresql://${c.host}:${c.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/${c.databaseName}"
    }

    /**
     * Drop the public schema and re-apply every migration found in `database/`.
     * Tests call this in @BeforeEach.
     */
    fun freshSchema() {
        DriverManager.getConnection(jdbcUrl(), container.username, container.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP SCHEMA IF EXISTS public CASCADE")
                st.execute("CREATE SCHEMA public")
            }
            val migrations = resolveMigrationDir().listFiles { f ->
                f.isFile && f.name.endsWith(".sql")
            }?.sortedBy { it.name } ?: emptyList()
            check(migrations.isNotEmpty()) {
                "no SQL migrations found in ${resolveMigrationDir().absolutePath}"
            }
            for (file in migrations) {
                conn.createStatement().use { st -> st.execute(file.readText()) }
            }
        }
    }

    private fun resolveMigrationDir(): File {
        // Test working dir is the module dir (`core/`); migrations live at repo root.
        val candidates = listOf(
            File("database"),
            File("../database"),
            File("../../database"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate database/ migration dir; tried: ${candidates.map { it.absolutePath }}")
    }
}
