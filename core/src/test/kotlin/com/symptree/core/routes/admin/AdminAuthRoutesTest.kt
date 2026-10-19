package com.symptree.core.routes.admin

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.routes.admin.AdminTestApp.installAdmin
import com.symptree.core.routes.admin.AdminTestApp.jsonClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-MG7TSR.2 / .3 — `POST /api/v1/admin/login` + the token auth gate.
 *
 * Login returns a signed bearer carrying clinic_id + clinic_user_id + role + expiry;
 * bad credentials return 401 with an identical response shape for unknown-email vs
 * wrong-password (no user enumeration). The auth gate rejects missing/expired/garbage
 * tokens with 401 and enforces the role gate with 403.
 */
class AdminAuthRoutesTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo
    private var clinicId: Long = 0L

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "auth-clinic").clinic.id
    }

    private fun loginBody(slug: String, email: String, password: String) =
        """{"clinic_slug":"$slug","email":"$email","password":"$password"}"""

    // ---------------- login ----------------

    @Test
    fun `AC-2 login with valid credentials returns 200 and a bearer token`() = testApplication {
        AdminFixtures.seedUser(userRepo, clinicId, "admin@x.io", "pw-correct", "admin")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("auth-clinic", "admin@x.io", "pw-correct"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val token = json["token"]?.jsonPrimitive?.contentOrNull
            ?: fail("login response must carry a token; got: $json")
        assertTrue(token.isNotBlank(), "token must be non-blank")

        // The minted token must verify against the SAME service the app uses and
        // carry this clinic's id (clinic_id comes from the token, never a param).
        val claims = AdminFixtures.tokenService().verify(token)
            ?: fail("issued token must verify")
        assertEquals(clinicId, claims.clinicId)
        assertEquals("admin", claims.role)
    }

    @Test
    fun `AC-FXJEVE-9 login response carries the user's role for SPA UI gating`() = testApplication {
        AdminFixtures.seedUser(userRepo, clinicId, "op@x.io", "pw-correct", "operator")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("auth-clinic", "op@x.io", "pw-correct"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("operator", json["role"]?.jsonPrimitive?.contentOrNull,
            "login response must echo the user's role (advisory UI data; server still enforces via the token)")
    }

    @Test
    fun `AC-2 login with wrong password returns 401`() = testApplication {
        AdminFixtures.seedUser(userRepo, clinicId, "admin@x.io", "pw-correct", "admin")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("auth-clinic", "admin@x.io", "pw-WRONG"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `AC-2 unknown email and wrong password return the same 401 response shape - no enumeration`() = testApplication {
        AdminFixtures.seedUser(userRepo, clinicId, "real@x.io", "pw-correct", "admin")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val wrongPw = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("auth-clinic", "real@x.io", "pw-WRONG"))
        }
        val unknownEmail = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("auth-clinic", "ghost@x.io", "pw-correct"))
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongPw.status)
        assertEquals(HttpStatusCode.Unauthorized, unknownEmail.status)
        assertEquals(
            wrongPw.bodyAsText(), unknownEmail.bodyAsText(),
            "unknown-email and wrong-password must be indistinguishable (no user enumeration)",
        )
    }

    @Test
    fun `AC-2 login against the wrong clinic_slug does not authenticate a foreign user`() = testApplication {
        // Seed a user in our clinic, then create a second clinic and try to log the
        // same email in against THAT clinic — must fail (clinic-scoped credentials).
        AdminFixtures.seedUser(userRepo, clinicId, "scoped@x.io", "pw-correct", "admin")
        val otherClinic = Fixtures.fixtureClinic(clinicRepo, slug = "other-clinic").clinic.id
        assertTrue(otherClinic != clinicId)
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody("other-clinic", "scoped@x.io", "pw-correct"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status,
            "credentials are clinic-scoped; the email must not authenticate against a different clinic")
    }

    // ---------------- auth gate ----------------

    @Test
    fun `AC-3 a protected admin route without a bearer token returns 401`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/doctors")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `AC-3 a protected admin route with a garbage bearer token returns 401`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/doctors") {
            header("Authorization", "Bearer not-a-real-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `AC-3 a protected admin route with an EXPIRED token returns 401`() = testApplication {
        val user = AdminFixtures.seedUser(userRepo, clinicId, "exp@x.io", "pw-correct", "admin")
        // Mint a token, then run the app with a service whose clock is past the TTL.
        val issued = AdminFixtures.tokenFor(AdminFixtures.tokenService(), user)
        val laterClock = Clock.fixed(AdminFixtures.FIXED_NOW.plus(Duration.ofHours(9)), ZoneOffset.UTC)
        installAdmin(AdminTestApp.deps(tokenService = AdminFixtures.tokenService(clock = laterClock)))
        val client = jsonClient()

        val resp = client.get("/api/v1/admin/doctors") {
            header("Authorization", "Bearer $issued")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status, "an expired token must be rejected with 401")
    }

    @Test
    fun `AC-4 operator role is forbidden from doctor writes - 403`() = testApplication {
        val operator = AdminFixtures.seedUser(userRepo, clinicId, "op@x.io", "pw-correct", "operator")
        val token = AdminFixtures.tokenFor(AdminFixtures.tokenService(), operator)
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.post("/api/v1/admin/doctors") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"full_name":"Dr X","specialist_codes":["therapist"],"slot_minutes":30}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status,
            "operator must not be allowed to create doctors")
    }
}
