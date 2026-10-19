package com.symptree.core.routes.admin

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.model.NewDoctor
import com.symptree.core.routes.admin.AdminTestApp.installAdmin
import com.symptree.core.routes.admin.AdminTestApp.jsonClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * FR-XKH348 / FR-FXJEVE — `GET /api/v1/admin/clinic-settings` exposes the clinic's
 * settings (semantic) to the SPA: `first_day_of_week`, scheduling `timezone`,
 * `min_lead_hours` (FR-FXJEVE), and the language set. `PUT` (FR-FXJEVE) edits the
 * same object — admin-only, token-scoped, validated. `clinic_id` comes only from
 * the token.
 */
class ClinicSettingsRoutesTest {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var userRepo: ClinicUserRepo

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        userRepo = ClinicUserRepo(db)
    }

    private fun tokenForClinic(clinicId: Long, role: String = "admin") = AdminFixtures.tokenFor(
        AdminFixtures.tokenService(),
        AdminFixtures.seedUser(userRepo, clinicId, "$role@x.io", role = role),
    )

    // A full, valid settings PUT body.
    private fun settingsBody(
        timezone: String = "Asia/Almaty",
        minLeadHours: Int = 48,
        firstDayOfWeek: Int = 0,
        defaultLanguage: String = "kk",
        allowedLanguages: List<String> = listOf("ru", "kk"),
    ) = buildJsonObject {
        put("timezone", timezone)
        put("min_lead_hours", minLeadHours)
        put("first_day_of_week", firstDayOfWeek)
        put("default_language", defaultLanguage)
        put("allowed_languages", buildJsonArrayOf(allowedLanguages))
    }.toString()

    @Test
    fun `defaults first_day_of_week to Monday (1), timezone to UTC, min_lead_hours to 24 when unset`() =
        testApplication {
            val clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "cs-default").clinic.id
            val token = tokenForClinic(clinicId)
            installAdmin(AdminTestApp.deps())
            val client = jsonClient()

            val resp = client.get("/api/v1/admin/clinic-settings") { header("Authorization", "Bearer $token") }
            assertEquals(HttpStatusCode.OK, resp.status, "got ${resp.bodyAsText()}")
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("1", obj["first_day_of_week"]?.jsonPrimitive?.content, "default first day = Monday (1)")
            assertEquals("UTC", obj["timezone"]?.jsonPrimitive?.content, "default timezone = UTC")
            assertEquals("24", obj["min_lead_hours"]?.jsonPrimitive?.content, "default min_lead_hours = 24")
            assertEquals("ru", obj["default_language"]?.jsonPrimitive?.content)
        }

    @Test
    fun `returns the clinic's configured first_day_of_week, timezone and min_lead_hours`() = testApplication {
        val config = buildJsonObject {
            putJsonObject("preferences") {
                put("timezone", "Asia/Almaty")
                put("first_day_of_week", 0) // Sunday-first clinic
                put("min_lead_hours", 2)
            }
        }
        val clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "cs-custom", config = config).clinic.id
        val token = tokenForClinic(clinicId)
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.get("/api/v1/admin/clinic-settings") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status, "got ${resp.bodyAsText()}")
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("0", obj["first_day_of_week"]?.jsonPrimitive?.content, "Sunday-first configured")
        assertEquals("Asia/Almaty", obj["timezone"]?.jsonPrimitive?.content)
        assertEquals("2", obj["min_lead_hours"]?.jsonPrimitive?.content)
    }

    @Test
    fun `requires authentication`() = testApplication {
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()
        val resp = client.get("/api/v1/admin/clinic-settings")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // --- FR-FXJEVE: PUT ---

    @Test
    fun `admin PUT round-trips and a subsequent GET returns the written values (AC-FXJEVE_2)`() = testApplication {
        val clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "cs-put").clinic.id
        val token = tokenForClinic(clinicId, role = "admin")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val putResp = client.put("/api/v1/admin/clinic-settings") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(settingsBody(timezone = "Asia/Almaty", minLeadHours = 48, firstDayOfWeek = 0, defaultLanguage = "kk"))
        }
        assertEquals(HttpStatusCode.OK, putResp.status, "got ${putResp.bodyAsText()}")
        val putObj = Json.parseToJsonElement(putResp.bodyAsText()).jsonObject
        assertEquals("Asia/Almaty", putObj["timezone"]?.jsonPrimitive?.content)
        assertEquals(48, putObj["min_lead_hours"]?.jsonPrimitive?.int)
        assertEquals(0, putObj["first_day_of_week"]?.jsonPrimitive?.int)
        assertEquals("kk", putObj["default_language"]?.jsonPrimitive?.content)

        val getResp = client.get("/api/v1/admin/clinic-settings") { header("Authorization", "Bearer $token") }
        assertEquals(putResp.bodyAsText(), getResp.bodyAsText(), "PUT response must equal a subsequent GET")
    }

    @Test
    fun `operator PUT is forbidden and does not mutate (AC-FXJEVE_3)`() = testApplication {
        val clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "cs-op").clinic.id
        val token = tokenForClinic(clinicId, role = "operator")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.put("/api/v1/admin/clinic-settings") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(settingsBody(timezone = "Asia/Almaty"))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status, "got ${resp.bodyAsText()}")

        // Unchanged: still the fixture default timezone (UTC).
        val clinic = clinicRepo.findById(clinicId)
        assertNotNull(clinic)
        assertEquals(null, clinic.config["preferences"]?.jsonObject?.get("timezone"), "operator must not write timezone")
    }

    @Test
    fun `rejects invalid field values with 422 (AC-FXJEVE_4)`() = testApplication {
        val clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "cs-422").clinic.id
        val token = tokenForClinic(clinicId, role = "admin")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val cases = listOf(
            "bad timezone" to settingsBody(timezone = "Mars/Olympus"),
            "negative min_lead" to settingsBody(minLeadHours = -1),
            "over-max min_lead" to settingsBody(minLeadHours = 169),
            "negative first_day" to settingsBody(firstDayOfWeek = -1),
            "over-max first_day" to settingsBody(firstDayOfWeek = 7),
        )
        for ((label, body) in cases) {
            val resp = client.put("/api/v1/admin/clinic-settings") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status, "$label should be 422, got ${resp.status}")
        }
    }

    @Test
    fun `enforces language safe-edit guardrails with 422 (AC-FXJEVE_5)`() = testApplication {
        val clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "cs-lang").clinic.id
        val token = tokenForClinic(clinicId, role = "admin")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val cases = listOf(
            "empty allowed" to settingsBody(defaultLanguage = "ru", allowedLanguages = emptyList()),
            "default not member" to settingsBody(defaultLanguage = "kk", allowedLanguages = listOf("ru")),
            "unsupported code" to settingsBody(defaultLanguage = "ru", allowedLanguages = listOf("ru", "en")),
        )
        for ((label, body) in cases) {
            val resp = client.put("/api/v1/admin/clinic-settings") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status, "$label should be 422, got ${resp.status}")
        }
    }

    @Test
    fun `merge preserves unrelated config keys and updates language columns (AC-FXJEVE_6)`() = testApplication {
        val config = buildJsonObject {
            putJsonObject("preferences") {
                put("min_lead_hours", 2)
                put("some_other_pref", "keep-me")
            }
        }
        val clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "cs-merge", config = config).clinic.id
        // The fixture stamps a real api_key_hash into config — capture it to prove the
        // settings write preserves unrelated top-level config keys verbatim.
        val apiKeyHashBefore = clinicRepo.findById(clinicId)?.config?.get("api_key_hash")?.jsonPrimitive?.content
        assertNotNull(apiKeyHashBefore)
        val token = tokenForClinic(clinicId, role = "admin")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        val resp = client.put("/api/v1/admin/clinic-settings") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(settingsBody(timezone = "Asia/Almaty", minLeadHours = 48, defaultLanguage = "kk", allowedLanguages = listOf("kk")))
        }
        assertEquals(HttpStatusCode.OK, resp.status, "got ${resp.bodyAsText()}")

        val clinic = clinicRepo.findById(clinicId)
        assertNotNull(clinic)
        val prefs = clinic.config["preferences"]?.jsonObject
        assertNotNull(prefs)
        // Settings keys updated.
        assertEquals("Asia/Almaty", prefs["timezone"]?.jsonPrimitive?.content)
        assertEquals("48", prefs["min_lead_hours"]?.jsonPrimitive?.content)
        // Unrelated preference + top-level key preserved.
        assertEquals("keep-me", prefs["some_other_pref"]?.jsonPrimitive?.content)
        assertEquals(apiKeyHashBefore, clinic.config["api_key_hash"]?.jsonPrimitive?.content)
        // Language columns updated.
        assertEquals("kk", clinic.defaultLanguage)
        assertEquals(listOf("kk"), clinic.allowedLanguages)
    }

    @Test
    fun `a timezone change is accepted even when a future booking exists - bookings are not touched (AC-FXJEVE_7)`() =
        testApplication {
            val db = PostgresTestSupport.database()
            val clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "cs-tz").clinic.id
            val token = tokenForClinic(clinicId, role = "admin")

            // Seed a confirmed future booking for this clinic.
            val doctorRepo = DoctorRepo(db)
            val sessionRepo = SessionRepo(db)
            val doc = doctorRepo.insert(NewDoctor(clinicId, "internal", "tz-doc", "Dr TZ", listOf("therapist"), 30, true))
            val sessionId = sessionRepo.insertActive(clinicId, "TELEGRAM", "tg-tz", "ru")
            val slotAt = Instant.parse("2026-08-01T10:00:00Z")
            val bookingId = db.getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO bookings (clinic_id, session_id, source_id, external_doctor_id, " +
                        "doctor_identity_id, specialist_code, slot_at, slot_end, external_appointment_id, status) " +
                        "VALUES (?, ?, 'internal', 'tz-doc', ?, 'therapist', ?, ?, NULL, 'confirmed') RETURNING id",
                ).use { ps ->
                    ps.setLong(1, clinicId)
                    ps.setLong(2, sessionId)
                    ps.setLong(3, doc.identityId)
                    ps.setTimestamp(4, Timestamp.from(slotAt))
                    ps.setTimestamp(5, Timestamp.from(slotAt.plusSeconds(1800)))
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }

            installAdmin(AdminTestApp.deps())
            val client = jsonClient()
            val resp = client.put("/api/v1/admin/clinic-settings") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(settingsBody(timezone = "Asia/Aqtobe"))
            }
            assertEquals(HttpStatusCode.OK, resp.status, "timezone change must succeed despite a future booking")

            // The booking's absolute UTC instant is untouched.
            val afterSlot = db.getConnection().use { conn ->
                conn.prepareStatement("SELECT slot_at FROM bookings WHERE id = ?").use { ps ->
                    ps.setLong(1, bookingId)
                    ps.executeQuery().use { rs -> rs.next(); rs.getTimestamp(1).toInstant() }
                }
            }
            assertEquals(slotAt, afterSlot, "the booking's stored UTC slot must not move when the timezone changes")
        }

    @Test
    fun `clinic_id is token-derived, never body-supplied, and never crosses tenants (AC-FXJEVE_8)`() = testApplication {
        val clinicA = Fixtures.fixtureClinic(clinicRepo, slug = "cs-iso-a").clinic.id
        val clinicB = Fixtures.fixtureClinic(clinicRepo, slug = "cs-iso-b").clinic.id
        val tokenA = tokenForClinic(clinicA, role = "admin")
        installAdmin(AdminTestApp.deps())
        val client = jsonClient()

        // Body even carries a (bogus) clinic_id pointing at B — it must be ignored.
        val body = buildJsonObject {
            put("clinic_id", clinicB)
            put("timezone", "Asia/Almaty")
            put("min_lead_hours", 48)
            put("first_day_of_week", 0)
            put("default_language", "kk")
            put("allowed_languages", buildJsonArrayOf(listOf("kk")))
        }.toString()

        val resp = client.put("/api/v1/admin/clinic-settings") {
            header("Authorization", "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, resp.status, "got ${resp.bodyAsText()}")

        // A changed; B untouched.
        assertEquals("kk", clinicRepo.findById(clinicA)?.defaultLanguage)
        assertEquals("ru", clinicRepo.findById(clinicB)?.defaultLanguage, "clinic B must be untouched")
    }
}

private fun buildJsonArrayOf(values: List<String>) = buildJsonArray { values.forEach { add(it) } }
