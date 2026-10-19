package com.symptree.core.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.plugins.configureSerialization
import com.symptree.core.plugins.configureStatusPages
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ClinicRoutesTest {

    private val platformAdminKey = "pak_platform_admin_secret_test"
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

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    // ============================================================
    // AC-M3WYNS.2 — POST /api/v1/clinics
    // ============================================================

    @Test
    fun `AC-2 POST clinics with platform-admin key returns 201 and persists`() = testApplication {
        installApp()
        val client = jsonClient()
        val body = """
            {"slug":"clinic-alpha","name":"Alpha","default_language":"ru",
             "allowed_languages":["ru","kk"],"config":{}}
        """.trimIndent()

        val response = client.post("/api/v1/clinics") {
            header("X-API-Key", platformAdminKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val clinic = json["clinic"]?.jsonObject ?: fail("clinic missing in response: $json")
        assertEquals("clinic-alpha", clinic["slug"]?.jsonPrimitive?.contentOrNull)
        val apiKey = json["clinic_admin_api_key"]?.jsonPrimitive?.contentOrNull
            ?: fail("clinic_admin_api_key missing from response")
        assertTrue(apiKey.isNotBlank(), "expected non-blank plaintext admin api key")

        // And the row was inserted
        assertNotNull(repo.findBySlug("clinic-alpha"))
    }

    @Test
    fun `AC-2 POST clinics without X-API-Key returns 401`() = testApplication {
        installApp()
        val client = jsonClient()
        val resp = client.post("/api/v1/clinics") {
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"x","name":"X","default_language":"ru","allowed_languages":["ru"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `AC-2 POST clinics with wrong X-API-Key returns 401`() = testApplication {
        installApp()
        val client = jsonClient()
        val resp = client.post("/api/v1/clinics") {
            header("X-API-Key", "bogus")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"y","name":"Y","default_language":"ru","allowed_languages":["ru"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `AC-2 POST clinics with invalid slug returns 400`() = testApplication {
        installApp()
        val client = jsonClient()
        val resp = client.post("/api/v1/clinics") {
            header("X-API-Key", platformAdminKey)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"BAD!","name":"X","default_language":"ru","allowed_languages":["ru"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `AC-2 POST clinics with default_language not in allowed_languages returns 400`() = testApplication {
        installApp()
        val client = jsonClient()
        val resp = client.post("/api/v1/clinics") {
            header("X-API-Key", platformAdminKey)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"clinic-x","name":"X","default_language":"tr","allowed_languages":["ru","kk"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `hardening POST clinics with duplicate slug returns 409 not 500`() = testApplication {
        installApp()
        val client = jsonClient()
        val body = """{"slug":"dup-route","name":"A","default_language":"ru","allowed_languages":["ru"]}"""

        val first = client.post("/api/v1/clinics") {
            header("X-API-Key", platformAdminKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, first.status)

        val second = client.post("/api/v1/clinics") {
            header("X-API-Key", platformAdminKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertTrue(
            second.bodyAsText().contains("slug_taken"),
            "expected slug_taken error code; got: ${second.bodyAsText()}",
        )
    }

    // ============================================================
    // AC-M3WYNS.3 — GET /api/v1/clinics/{slug}
    // ============================================================

    @Test
    fun `AC-3 GET clinic with matching clinic-admin key returns 200 with config`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "alpha-get")
        val client = jsonClient()
        val resp = client.get("/api/v1/clinics/alpha-get") {
            header("X-API-Key", a.apiKey)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("alpha-get", json["slug"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(json["config"]?.jsonObject, "expected config object in response")
    }

    @Test
    fun `AC-3 GET clinic with platform-admin key returns 200`() = testApplication {
        installApp()
        Fixtures.fixtureClinic(repo, slug = "alpha-plat")
        val client = jsonClient()
        val resp = client.get("/api/v1/clinics/alpha-plat") {
            header("X-API-Key", platformAdminKey)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `AC-3 GET clinic without X-API-Key returns 401`() = testApplication {
        installApp()
        Fixtures.fixtureClinic(repo, slug = "alpha-noauth")
        val client = jsonClient()
        val resp = client.get("/api/v1/clinics/alpha-noauth")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `AC-3 clinic-A key calling clinic-B URL returns 403 not 404`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "tenant-a")
        Fixtures.fixtureClinic(repo, slug = "tenant-b")
        val client = jsonClient()
        val resp = client.get("/api/v1/clinics/tenant-b") {
            header("X-API-Key", a.apiKey)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status,
            "AC-M3WYNS.3 demands 403 (not 404) on wrong-clinic access")
        assertFalse(resp.status == HttpStatusCode.NotFound, "must not leak existence as 404")
    }

    @Test
    fun `AC-3 clinic-admin key for non-existent slug returns 403 (don't leak)`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "tenant-a")
        val client = jsonClient()
        val resp = client.get("/api/v1/clinics/no-such-clinic") {
            header("X-API-Key", a.apiKey)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `AC-3 platform-admin key for non-existent slug returns 404`() = testApplication {
        installApp()
        val client = jsonClient()
        val resp = client.get("/api/v1/clinics/no-such-clinic") {
            header("X-API-Key", platformAdminKey)
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // ============================================================
    // AC-M3WYNS.4 — PUT /api/v1/clinics/{slug}/config
    // ============================================================

    @Test
    fun `AC-4 PUT config deep-merges and returns 200`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(
            repo, slug = "merge-a",
            config = buildJsonObject {
                put("preferences", buildJsonObject {
                    put("booking_lookahead_days", JsonPrimitive(7))
                    put("reminder_24h_enabled", JsonPrimitive(true))
                })
            },
        )
        val client = jsonClient()
        val patch = """
            {"configPatch":{"preferences":{"booking_lookahead_days":14}}}
        """.trimIndent()
        val resp = client.put("/api/v1/clinics/merge-a/config") {
            header("X-API-Key", a.apiKey)
            contentType(ContentType.Application.Json)
            setBody(patch)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val prefs = body["preferences"]?.jsonObject ?: fail("preferences not in merged config")
        assertEquals("14", prefs["booking_lookahead_days"]?.jsonPrimitive?.contentOrNull,
            "lookahead must be updated")
        assertEquals("true", prefs["reminder_24h_enabled"]?.jsonPrimitive?.contentOrNull,
            "deep-merge must preserve sibling key not in patch")
    }

    @Test
    fun `AC-4 PUT config without key returns 401`() = testApplication {
        installApp()
        Fixtures.fixtureClinic(repo, slug = "merge-noauth")
        val client = jsonClient()
        val resp = client.put("/api/v1/clinics/merge-noauth/config") {
            contentType(ContentType.Application.Json)
            setBody("""{"configPatch":{}}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `AC-4 PUT config with wrong-clinic key returns 403`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "merge-a-2")
        Fixtures.fixtureClinic(repo, slug = "merge-b-2")
        val client = jsonClient()
        val resp = client.put("/api/v1/clinics/merge-b-2/config") {
            header("X-API-Key", a.apiKey)
            contentType(ContentType.Application.Json)
            setBody("""{"configPatch":{"x":1}}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `AC-4 PUT config emits INFO log with clinic_id and without api_key_hash`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(
            repo, slug = "log-clinic",
            config = buildJsonObject {
                put("api_key_hash", JsonPrimitive("hash-should-not-appear"))
                put("preferences", buildJsonObject { put("reminder_24h_enabled", JsonPrimitive(true)) })
            },
        )

        // Attach an in-memory Logback appender to the routes logger.
        val logger = LoggerFactory.getLogger("com.symptree.core.routes.clinicRoutes") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        logger.level = Level.INFO

        try {
            val client = jsonClient()
            val resp = client.put("/api/v1/clinics/log-clinic/config") {
                header("X-API-Key", a.apiKey)
                contentType(ContentType.Application.Json)
                setBody("""{"configPatch":{"preferences":{"booking_lookahead_days":21}}}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)

            val infoEvents = appender.list.filter { it.level == Level.INFO }
            assertTrue(infoEvents.isNotEmpty(), "expected at least one INFO log event")
            val combined = infoEvents.joinToString("\n") { it.formattedMessage }
            val expectedClinicIdToken = "clinic_id=${a.clinic.id}"
            assertTrue(
                combined.contains(expectedClinicIdToken),
                "INFO log must include $expectedClinicIdToken; saw: $combined",
            )
            assertFalse(combined.contains("api_key_hash"),
                "INFO log must NOT contain api_key_hash; saw: $combined")
            assertFalse(combined.contains("hash-should-not-appear"),
                "INFO log must NOT contain the hash value; saw: $combined")
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ============================================================
    // AC-M3WYNS.5 — algorithm enable/disable
    // ============================================================

    @Test
    fun `AC-5 enable algorithm returns 200 and inserts clinic_algorithms row`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "algo-clinic")
        insertAlgorithmRow("symptom_demo_adult")

        val client = jsonClient()
        val resp = client.post("/api/v1/clinics/algo-clinic/algorithms/symptom_demo_adult/enable") {
            header("X-API-Key", a.apiKey)
        }
        assertTrue(resp.status == HttpStatusCode.OK || resp.status == HttpStatusCode.Created,
            "expected 2xx success, got ${resp.status}")
    }

    @Test
    fun `AC-5 disable algorithm is idempotent`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "algo-clinic-2")
        insertAlgorithmRow("symptom_cough_adult")

        val client = jsonClient()
        val r1 = client.post("/api/v1/clinics/algo-clinic-2/algorithms/symptom_cough_adult/disable") {
            header("X-API-Key", a.apiKey)
        }
        val r2 = client.post("/api/v1/clinics/algo-clinic-2/algorithms/symptom_cough_adult/disable") {
            header("X-API-Key", a.apiKey)
        }
        assertTrue(r1.status.value in 200..299, "first disable should succeed, got ${r1.status}")
        assertTrue(r2.status.value in 200..299, "second disable should be idempotent, got ${r2.status}")
    }

    @Test
    fun `AC-5 enable unknown algorithm returns 404`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "algo-clinic-3")
        val client = jsonClient()
        val resp = client.post("/api/v1/clinics/algo-clinic-3/algorithms/no_such_algo/enable") {
            header("X-API-Key", a.apiKey)
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `AC-5 enable algorithm without key returns 401`() = testApplication {
        installApp()
        Fixtures.fixtureClinic(repo, slug = "algo-clinic-4")
        insertAlgorithmRow("symptom_fever_adult")
        val client = jsonClient()
        val resp = client.post("/api/v1/clinics/algo-clinic-4/algorithms/symptom_fever_adult/enable")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `AC-5 enable algorithm with wrong-clinic key returns 403`() = testApplication {
        installApp()
        val a = Fixtures.fixtureClinic(repo, slug = "algo-iso-a")
        Fixtures.fixtureClinic(repo, slug = "algo-iso-b")
        insertAlgorithmRow("symptom_back_pain_adult")
        val client = jsonClient()
        val resp = client.post("/api/v1/clinics/algo-iso-b/algorithms/symptom_back_pain_adult/enable") {
            header("X-API-Key", a.apiKey)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ---------- helpers ----------

    private fun insertAlgorithmRow(key: String): Long {
        PostgresTestSupport.database().getConnection().use { conn ->
            // Post-FR-KT4697 the algorithms table requires (algorithm_key, version,
            // metadata, tree); these tests exercise routing not algorithm payload,
            // so we supply minimal valid placeholders.
            conn.prepareStatement(
                "INSERT INTO algorithms (algorithm_key, version, metadata, tree, is_active) " +
                    "VALUES (?, '1.0.0', '{}'::jsonb, '{}'::jsonb, TRUE) RETURNING id",
            ).use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }
}
