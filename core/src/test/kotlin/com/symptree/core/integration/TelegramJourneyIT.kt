package com.symptree.core.integration

import com.symptree.core.Fixtures
import com.symptree.core.crypto.PiiCipher
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.llm.FakeLlmRouterClient
import com.symptree.core.llm.Intent
import com.symptree.core.llm.IntentClassification
import com.symptree.core.llm.OptionMapping
import com.symptree.core.plugins.configureSerialization
import com.symptree.core.plugins.configureStatusPages
import com.symptree.core.routes.botIngressRoutes
import com.symptree.core.symptomizer.AlgorithmSeedLoader
import com.symptree.core.symptomizer.SessionService
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-W3WDWH.10 — end-to-end Telegram journey test.
 *
 * Replays a recorded sequence of incoming Telegram updates through the
 * `/api/v1/clinics/{slug}/bot/ingress` route against a Testcontainers
 * Postgres backend seeded with the shipped demo algorithm (`symptom_demo_adult`)
 * plus the FakeLlmRouterClient. Asserts the journey reaches a `ConclusionNode`
 * in both RU and KK.
 *
 * Lives at `core/src/test/kotlin/com/symptree/core/integration/TelegramJourneyIT.kt`
 * per the FR Testing notes.
 */
class TelegramJourneyIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var algorithmRepo: AlgorithmRepo
    private lateinit var sessionRepo: SessionRepo

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        algorithmRepo = AlgorithmRepo(db)
        sessionRepo = SessionRepo(db)
        AlgorithmSeedLoader.seed(algorithmRepo)
    }

    private fun ApplicationTestBuilder.installApp(llm: FakeLlmRouterClient) {
        val service = SessionService(
            clinicRepo = clinicRepo,
            algorithmRepo = algorithmRepo,
            sessionRepo = sessionRepo,
            llm = llm,
            piiCipher = PiiCipher(ByteArray(32) { it.toByte() }),
        )
        application {
            configureSerialization()
            configureStatusPages()
            routing { botIngressRoutes(clinicRepo, service, "bot_test") }
        }
    }

    @Test
    fun `RU journey through symptom_demo_adult reaches a ConclusionNode`() = testApplication {
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "tj-ru",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru", "kk"),
        )
        // Enable the demo algorithm seeded by AlgorithmSeedLoader.
        val algorithmId = algorithmRepo.findByKeyAndVersion("symptom_demo_adult", "1.0.0")
            ?: error("seed must insert symptom_demo_adult")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        // Always pick "no" — walks the algorithm down a deterministic path.
        installApp(
            FakeLlmRouterClient(
                intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
                mappingResponse = OptionMapping(pickedOptionValue = "no", confidence = 0.9, clarifyingQuestion = null),
            ),
        )

        val payloads = mutableListOf<String>()
        val updates = listOf("Болит голова", "нет", "нет", "нет", "нет", "нет", "нет", "нет", "нет", "нет")
        for ((i, text) in updates.withIndex()) {
            val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
                contentType(ContentType.Application.Json)
                header("X-API-Key", "bot_test")
                setBody("""{"user":{"platform":"TELEGRAM","id":"tg-ru","languageCode":"ru"},"text":"$text"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, "step $i")
            payloads += response.bodyAsText()
        }

        // FR-VWRNDR — the wire is semantic: a conclusion surfaces as a typed
        // `Conclusion` outcome carrying an `urgency` enum field (no icon/chrome).
        assertTrue(
            payloads.any { p -> p.contains("\"Conclusion\"") && p.contains("\"urgency\"") },
            "RU journey must reach a ConclusionNode (semantic Conclusion outcome); got payloads:\n${payloads.joinToString("\n---\n")}",
        )
        // Final session row must be in 'concluded' status.
        val activeAfter = sessionRepo.findActiveSession(clinic.id, "TELEGRAM", "tg-ru")
        assertTrue(activeAfter == null, "after reaching a conclusion the active session must be closed")
    }

    @Test
    fun `KK journey through symptom_demo_adult reaches a ConclusionNode`() = testApplication {
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "tj-kk",
            defaultLanguage = "kk",
            allowedLanguages = listOf("ru", "kk"),
        )
        val algorithmId = algorithmRepo.findByKeyAndVersion("symptom_demo_adult", "1.0.0")
            ?: error("seed must insert symptom_demo_adult")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        installApp(
            FakeLlmRouterClient(
                intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
                mappingResponse = OptionMapping(pickedOptionValue = "no", confidence = 0.9, clarifyingQuestion = null),
            ),
        )

        val payloads = mutableListOf<String>()
        val updates = listOf("Басым ауырады", "жоқ", "жоқ", "жоқ", "жоқ", "жоқ", "жоқ", "жоқ", "жоқ", "жоқ")
        for ((i, text) in updates.withIndex()) {
            val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
                contentType(ContentType.Application.Json)
                header("X-API-Key", "bot_test")
                setBody("""{"user":{"platform":"TELEGRAM","id":"tg-kk","languageCode":"kk"},"text":"$text"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, "step $i")
            payloads += response.bodyAsText()
        }

        assertTrue(
            payloads.any { p -> p.contains("\"Conclusion\"") && p.contains("\"urgency\"") },
            "KK journey must reach a ConclusionNode (semantic Conclusion outcome); got payloads:\n${payloads.joinToString("\n---\n")}",
        )
    }
}
