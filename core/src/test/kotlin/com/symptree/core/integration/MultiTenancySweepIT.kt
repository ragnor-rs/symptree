package com.symptree.core.integration

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.plugins.configureSerialization
import com.symptree.core.plugins.configureStatusPages
import com.symptree.core.routes.clinicRoutes
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * AC-M3WYNS.7 — Cross-tenant access sweep.
 *
 * For every clinic-admin route, send a request where clinic-A's API key is used to address
 * clinic-B's resources. Each call MUST return 403 — never 404 (existence leak) and never 200.
 */
class MultiTenancySweepIT {

    private val platformAdminKey = "pak_sweep_test"
    private lateinit var repo: ClinicRepo
    private lateinit var clinicUserRepo: ClinicUserRepo

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        repo = ClinicRepo(db)
        clinicUserRepo = ClinicUserRepo(db)
    }

    private fun ApplicationTestBuilder.installApp() {
        application {
            configureSerialization()
            configureStatusPages()
            routing { clinicRoutes(repo, clinicUserRepo, platformAdminKey) }
        }
    }

    /** Each clinic-admin route shape — `slugB` is interpolated when generating the URL. */
    private data class RouteCase(
        val label: String,
        val method: HttpMethod,
        val urlTemplate: String,        // contains literal `{slug}` placeholder
        val body: String? = null,
    )

    private val clinicAdminRoutes = listOf(
        RouteCase("GET clinic", HttpMethod.Get, "/api/v1/clinics/{slug}"),
        RouteCase(
            "PUT clinic config", HttpMethod.Put,
            "/api/v1/clinics/{slug}/config",
            """{"configPatch":{"touched":"by-A"}}""",
        ),
        RouteCase(
            "POST enable algorithm", HttpMethod.Post,
            "/api/v1/clinics/{slug}/algorithms/symptom_demo_adult/enable",
        ),
        RouteCase(
            "POST disable algorithm", HttpMethod.Post,
            "/api/v1/clinics/{slug}/algorithms/symptom_demo_adult/disable",
        ),
    )

    @Test
    fun `AC-7 every clinic-admin route returns 403 when called with another clinic's key`() = testApplication {
        installApp()

        // Two clinics, plus a real algorithm so the route can resolve it past auth gating.
        val a = Fixtures.fixtureClinic(repo, slug = "tenant-sweep-a")
        Fixtures.fixtureClinic(repo, slug = "tenant-sweep-b")
        seedAlgorithm("symptom_demo_adult")

        val violations = mutableListOf<String>()
        for (case in clinicAdminRoutes) {
            val url = case.urlTemplate.replace("{slug}", "tenant-sweep-b")
            val response: HttpResponse = client.request(url) {
                method = case.method
                header("X-API-Key", a.apiKey)
                if (case.body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(case.body)
                }
            }
            if (response.status != HttpStatusCode.Forbidden) {
                violations.add("${case.label} (${case.method.value} $url) returned ${response.status}, expected 403")
            }
        }

        assertEquals(emptyList(), violations,
            "AC-M3WYNS.7: every cross-tenant call must return 403; violations:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `AC-7 cross-tenant responses never leak existence as 404 and never succeed as 200`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "leak-check-a")
        Fixtures.fixtureClinic(repo, slug = "leak-check-b")
        seedAlgorithm("symptom_demo_adult")

        for (case in clinicAdminRoutes) {
            val url = case.urlTemplate.replace("{slug}", "leak-check-b")
            val response = client.request(url) {
                method = case.method
                header("X-API-Key", a.apiKey)
                if (case.body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(case.body)
                }
            }
            assertNotEquals(
                HttpStatusCode.NotFound, response.status,
                "${case.label}: leaks existence as 404 instead of 403",
            )
            assertNotEquals(
                HttpStatusCode.OK, response.status,
                "${case.label}: succeeds as 200 with a foreign key",
            )
        }
    }

    private fun seedAlgorithm(key: String) {
        PostgresTestSupport.database().getConnection().use { conn ->
            // Post-FR-KT4697 the algorithms table requires (algorithm_key, version,
            // metadata, tree); the cross-tenant sweep doesn't exercise the
            // symptomizer payload, so minimal placeholders suffice.
            conn.prepareStatement(
                "INSERT INTO algorithms (algorithm_key, version, metadata, tree, is_active) " +
                    "VALUES (?, '1.0.0', '{}'::jsonb, '{}'::jsonb, TRUE)",
            ).use { ps ->
                ps.setString(1, key)
                ps.executeUpdate()
            }
        }
    }
}
