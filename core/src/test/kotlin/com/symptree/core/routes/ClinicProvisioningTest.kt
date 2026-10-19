package com.symptree.core.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.plugins.configureSerialization
import com.symptree.core.plugins.configureStatusPages
import com.symptree.core.routes.admin.AdminFixtures
import com.symptree.core.routes.admin.AdminTestApp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import com.symptree.core.routes.admin.adminRoutes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * FR-PRVSN2 — seed a clinic-admin login when `POST /api/v1/clinics` is called
 * with an `admin_email`. Mirrors ClinicRoutesTest's wiring (real Postgres via
 * PostgresTestSupport.freshSchema()), but exercises the provisioning path.
 */
class ClinicProvisioningTest {

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

    private fun createBody(slug: String, adminEmail: String?): String {
        val adminField = if (adminEmail == null) "" else ""","admin_email":"$adminEmail""""
        return """
            {"slug":"$slug","name":"Prov","default_language":"ru",
             "allowed_languages":["ru","kk"],"config":{}$adminField}
        """.trimIndent()
    }

    private suspend fun ApplicationTestBuilder.create(slug: String, adminEmail: String?) =
        jsonClient().post("/api/v1/clinics") {
            header("X-API-Key", platformAdminKey)
            contentType(ContentType.Application.Json)
            setBody(createBody(slug, adminEmail))
        }

    private fun countClinicUsers(clinicId: Long): Int {
        PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM clinic_users WHERE clinic_id = ?").use { ps ->
                ps.setLong(1, clinicId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    // ============================================================
    // AC-PRVSN2.1 — admin clinic_users row is provisioned
    // ============================================================

    @Test
    fun `AC-1 create with admin_email seeds an active admin clinic_users row`() = testApplication {
        installApp()
        val email = "boss@clinic.io"
        val resp = create("prov-seed", email)
        assertEquals(HttpStatusCode.Created, resp.status, "create-with-admin must 201; got ${resp.bodyAsText()}")

        val clinic = repo.findBySlug("prov-seed") ?: fail("clinic must be inserted")
        val user = clinicUserRepo.findByClinicAndEmail(clinic.id, email)
            ?: fail("a clinic_users row must exist for the seeded admin email")
        assertEquals("admin", user.role, "seeded user role must be admin")
        assertTrue(user.active, "seeded user must be active")
        assertEquals(email, user.email)
    }

    // ============================================================
    // AC-PRVSN2.2 — one-time admin block in the response
    // ============================================================

    @Test
    fun `AC-2 create with admin_email returns admin block with email and non-blank temp_password`() = testApplication {
        installApp()
        val email = "owner@clinic.io"
        val resp = create("prov-block", email)
        assertEquals(HttpStatusCode.Created, resp.status)

        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val admin = json["admin"]?.jsonObject
            ?: fail("response must carry an admin block when a user was seeded; got: $json")
        assertEquals(email, admin["email"]?.jsonPrimitive?.contentOrNull,
            "admin.email must equal the supplied admin_email")
        val tempPassword = admin["temp_password"]?.jsonPrimitive?.contentOrNull
            ?: fail("admin block must carry temp_password")
        assertTrue(tempPassword.isNotBlank(), "temp_password must be non-blank")
    }

    // ============================================================
    // AC-PRVSN2.3 — temp password entropy + distinct from api key
    // ============================================================

    @Test
    fun `AC-3 temp_password is high-entropy and distinct from the clinic_admin_api_key`() = testApplication {
        installApp()
        val resp = create("prov-entropy", "sec@clinic.io")
        assertEquals(HttpStatusCode.Created, resp.status)

        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val apiKey = json["clinic_admin_api_key"]?.jsonPrimitive?.contentOrNull
            ?: fail("clinic_admin_api_key must be present")
        val tempPassword = json["admin"]?.jsonObject?.get("temp_password")?.jsonPrimitive?.contentOrNull
            ?: fail("admin.temp_password must be present")

        assertTrue(
            tempPassword != apiKey,
            "temp_password must be a distinct value from clinic_admin_api_key",
        )
        // 32 bytes url-safe base64 (no padding) -> 43 chars; require >= 40 as a floor.
        assertTrue(
            tempPassword.length >= 40,
            "temp_password must encode >= 32 bytes of entropy (>= 40 chars); was ${tempPassword.length}",
        )
    }

    // ============================================================
    // AC-PRVSN2.4 — backward compatible (no admin_email)
    // ============================================================

    @Test
    fun `AC-4 create without admin_email seeds no user and omits the admin block`() = testApplication {
        installApp()
        val resp = create("prov-bare", null)
        assertEquals(HttpStatusCode.Created, resp.status)

        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val adminEl = json["admin"]
        assertTrue(
            adminEl == null || adminEl is JsonNull,
            "response must omit the admin block (null/absent) when no admin_email was supplied; got: $json",
        )

        // And NO clinic_users row exists for this clinic at all (count == 0).
        // Counting by clinic_id catches a stray seed under *any* email, which a
        // lookup by a single known address would miss.
        val clinic = repo.findBySlug("prov-bare") ?: fail("clinic must be inserted")
        assertEquals(
            0,
            countClinicUsers(clinic.id),
            "no clinic_users row may be seeded when admin_email is absent",
        )
    }

    // ============================================================
    // AC-PRVSN2.5 — invalid admin_email rejected before insert
    // ============================================================

    @Test
    fun `AC-5 invalid admin_email returns 400 validation_failed and creates no clinic`() = testApplication {
        installApp()
        val resp = jsonClient().post("/api/v1/clinics") {
            header("X-API-Key", platformAdminKey)
            contentType(ContentType.Application.Json)
            setBody(createBody("prov-bademail", "not-an-email"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(
            resp.bodyAsText().contains("validation_failed"),
            "expected validation_failed envelope; got: ${resp.bodyAsText()}",
        )
        assertNull(
            repo.findBySlug("prov-bademail"),
            "no clinic may be created when admin_email validation fails (validation runs before insert)",
        )
    }

    // ============================================================
    // AC-PRVSN2.7 — temp password + password_hash never logged
    // ============================================================

    @Test
    fun `AC-7 create path logs neither the temp_password nor a bcrypt hash`() = testApplication {
        installApp()

        val logger = LoggerFactory.getLogger("com.symptree.core.routes.clinicRoutes") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        logger.level = Level.TRACE

        try {
            val resp = create("prov-log", "logadmin@clinic.io")
            assertEquals(HttpStatusCode.Created, resp.status)

            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val tempPassword = json["admin"]?.jsonObject?.get("temp_password")?.jsonPrimitive?.contentOrNull
                ?: fail("admin.temp_password must be present")

            val combined = appender.list.joinToString("\n") { it.formattedMessage }
            assertFalse(
                combined.contains(tempPassword),
                "create-path logs must NOT contain the temp password; saw: $combined",
            )
            assertFalse(
                combined.contains("\$2a\$") || combined.contains("\$2b\$"),
                "create-path logs must NOT contain a bcrypt hash prefix; saw: $combined",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ============================================================
    // AC-PRVSN2.6 — end-to-end create -> admin login round-trip
    // ============================================================

    @Test
    fun `AC-6 a clinic created with admin_email can immediately log in via admin login`() = testApplication {
        // Mount BOTH the clinic-create routes and the admin-login routes against
        // the same DB so the seeded credential round-trips end to end.
        val email = "e2e@clinic.io"
        val deps = AdminTestApp.deps(tokenService = AdminFixtures.tokenService())
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                clinicRoutes(repo, clinicUserRepo, platformAdminKey)
                adminRoutes(deps)
            }
        }

        val client = jsonClient()

        // 1) Create the clinic with an admin email -> get the one-time temp password.
        val createResp = client.post("/api/v1/clinics") {
            header("X-API-Key", platformAdminKey)
            contentType(ContentType.Application.Json)
            setBody(createBody("prov-e2e", email))
        }
        assertEquals(HttpStatusCode.Created, createResp.status, "create-with-admin must 201")
        val createJson = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        val tempPassword = createJson["admin"]?.jsonObject?.get("temp_password")?.jsonPrimitive?.contentOrNull
            ?: fail("admin.temp_password must be present")

        // 2) Log in immediately with the returned temp password.
        val loginResp = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"clinic_slug":"prov-e2e","email":"$email","password":"$tempPassword"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResp.status,
            "the seeded admin must be able to log in with the temp password; got: ${loginResp.bodyAsText()}")
        val token = Json.parseToJsonElement(loginResp.bodyAsText()).jsonObject["token"]?.jsonPrimitive?.contentOrNull
            ?: fail("login must return a token")
        assertTrue(token.isNotBlank(), "bearer token must be non-blank")
        assertNotNull(token)
    }
}
