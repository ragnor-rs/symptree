package com.symptree.core.routes

import com.symptree.core.Fixtures
import com.symptree.core.crypto.PiiCipher
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.llm.FakeLlmRouterClient
import com.symptree.core.llm.Intent
import com.symptree.core.llm.IntentClassification
import com.symptree.core.llm.OptionMapping
import com.symptree.core.symptomizer.Algorithm
import com.symptree.core.symptomizer.AlgorithmMetadata
import com.symptree.core.symptomizer.ClinicAction
import com.symptree.core.symptomizer.ConclusionNode
import com.symptree.core.symptomizer.Option
import com.symptree.core.symptomizer.QuestionNode
import com.symptree.core.symptomizer.SessionService
import com.symptree.core.symptomizer.Urgency
import com.symptree.core.plugins.configureSerialization
import com.symptree.core.plugins.configureStatusPages
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * FR-VWRNDR — the `/bot/ingress` **semantic outcome contract** (AC-VWRNDR.2/.3/.4).
 *
 * `core` emits typed outcome DTOs carrying concrete fields only — `messaging` owns
 * all chrome. These tests assert the wire carries the semantic discriminators +
 * `core`-localized **domain** text (question/option/diagnosis/action), and that no
 * chrome literal, catalog key, urgency icon, or view format crosses the wire.
 * (The frontend reassembly half is covered by `messaging`'s `IngressRendererTest`.)
 */
class BotIngressRoutesTest {

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
    }

    private fun ApplicationTestBuilder.installApp(llm: FakeLlmRouterClient): Unit {
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
            routing {
                botIngressRoutes(clinicRepo, service, "bot_test")
            }
        }
    }

    // ---------- AC-VWRNDR.2 — Welcome is a semantic outcome, no chrome on the wire ----------

    @Test
    fun `start produces a semantic Welcome outcome carrying goal keys, not chrome`() = testApplication {
        installApp(FakeLlmRouterClient())
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "ac4-welcome",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru", "kk"),
        )

        val body = """
            {
              "user": {"platform":"TELEGRAM","id":"tg-1","languageCode":"ru"},
              "text": "/start"
            }
        """.trimIndent()

        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.bodyAsText()
        // Semantic Welcome variant carrying the typed goal keys.
        assertTrue(payload.contains("\"Welcome\""), "expected a Welcome outcome; got $payload")
        for (goal in listOf("symptom_consultation", "appointment_booking", "service_info")) {
            assertTrue(payload.contains(goal), "Welcome must carry goal key $goal; got $payload")
        }
        // No chrome / no view artifacts: the welcome text, the goal labels, the
        // `goal:` callback prefix, and any SendMenu action are the frontend's.
        assertFalse(payload.contains("Здравствуйте"), "welcome chrome text must not cross the wire; got $payload")
        assertFalse(payload.contains("goal:"), "the goal:<id> callback prefix is built by the frontend; got $payload")
        assertFalse(payload.contains("SendMenu"), "view actions must not cross the wire; got $payload")
    }

    // ---------- AC-VWRNDR.2 — GoalPrompt is semantic ----------

    @Test
    fun `picking symptom_consultation emits a semantic GoalPrompt, not the chrome string`() = testApplication {
        installApp(FakeLlmRouterClient())
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac5-symptom")

        val body = """
            {
              "user": {"platform":"TELEGRAM","id":"tg-5","languageCode":"ru"},
              "callbackData": "goal:symptom_consultation"
            }
        """.trimIndent()
        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.bodyAsText()
        assertTrue(payload.contains("\"GoalPrompt\""), "expected a GoalPrompt outcome; got $payload")
        assertTrue(payload.contains("main_complaint"), "GoalPrompt must carry the semantic kind; got $payload")
        assertFalse(payload.contains("Расскажите"), "the prompt chrome string must not cross the wire; got $payload")
    }

    // ---------- AC-VWRNDR.3 — domain (tree) text IS localized by core ----------

    @Test
    fun `free-form complaint emits NextQuestion with core-localized domain text and option labels`() = testApplication {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac5-prepick")
        val algorithmId = algorithmRepo.insert(simpleAlgorithm(), ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        installApp(FakeLlmRouterClient(intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null)))
        val body = """
            {
              "user": {"platform":"TELEGRAM","id":"tg-prepick","languageCode":"ru"},
              "text": "у меня болит голова"
            }
        """.trimIndent()
        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.bodyAsText()
        assertTrue(payload.contains("\"NextQuestion\""), "expected a NextQuestion outcome; got $payload")
        // AC-VWRNDR.3 — core localizes the algorithm-tree text + option labels to
        // the session language and ships concrete strings.
        assertTrue(payload.contains("Болит сейчас?"), "core must localize the question text; got $payload")
        assertTrue(
            payload.contains("Да") && payload.contains("Нет"),
            "core must localize the option labels; got $payload",
        )
    }

    // ---------- AC-VWRNDR.4 — Reprompt carries the semantic rejectReason only ----------

    @Test
    fun `LLM rejection emits a Reprompt with the semantic reason + the node's domain text`() = testApplication {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac6-reject")
        val algorithmId = algorithmRepo.insert(simpleAlgorithm(), ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        val rejectingLlm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
            mappingResponse = OptionMapping(pickedOptionValue = "maybe", confidence = 0.95, clarifyingQuestion = null),
        )
        installApp(rejectingLlm)

        client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-6","languageCode":"ru"},"text":"голова"}""")
        }
        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-6","languageCode":"ru"},"text":"может быть"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.bodyAsText()
        assertTrue(payload.contains("\"Reprompt\""), "expected a Reprompt outcome; got $payload")
        // AC-VWRNDR.4 — only the semantic rejectReason crosses the wire (no LLM
        // free-text, no chrome reprompt string).
        assertTrue(payload.contains("out_of_set"), "Reprompt must carry the semantic reason; got $payload")
        assertFalse(payload.contains("Я не смог сопоставить"), "the reprompt chrome must not cross the wire; got $payload")
        // The current node's domain text + options are carried so the frontend
        // can re-render the question above the menu.
        assertTrue(payload.contains("Болит сейчас?"), "Reprompt must carry the node's domain text; got $payload")
        assertTrue(payload.contains("Да") && payload.contains("Нет"), "Reprompt must carry the options; got $payload")
    }

    // ---------- AC-VWRNDR.2 — Conclusion is semantic (no icon, no CTA chrome) ----------

    @Test
    fun `BOOK_APPOINTMENT conclusion emits a semantic Conclusion with typed clinicAction + urgency`() = testApplication {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac7-book")
        val algorithmId = algorithmRepo.insert(simpleAlgorithm(), ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        val llm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
            mappingResponse = OptionMapping(pickedOptionValue = "no", confidence = 0.9, clarifyingQuestion = null),
        )
        installApp(llm)

        client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-7","languageCode":"ru"},"text":"болит"}""")
        }
        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-7","languageCode":"ru"},"text":"нет"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.bodyAsText()
        assertTrue(payload.contains("\"Conclusion\""), "expected a Conclusion outcome; got $payload")
        assertTrue(payload.contains("book_appointment"), "typed clinicAction must cross the wire; got $payload")
        assertTrue(payload.contains("routine"), "typed urgency must cross the wire; got $payload")
        // Domain text (diagnosis/action) is localized by core.
        assertTrue(payload.contains("терапевт"), "the action's domain text must cross the wire; got $payload")
        // No chrome: urgency icon, CTA labels, and the specialist header are the frontend's.
        assertFalse(payload.contains("📌"), "the urgency icon is a view artifact; got $payload")
        assertFalse(payload.contains("Записаться сейчас"), "the booking CTA chrome must not cross the wire; got $payload")
        assertFalse(payload.contains("Рекомендуемый специалист"), "no specialist chrome line; got $payload")
    }

    @Test
    fun `BYPASS_APPOINTMENT conclusion emits the emergency clinicAction + urgency, no icon`() = testApplication {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac7-bypass")
        val algorithmId = algorithmRepo.insert(bypassConclusionAlgorithm(), ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        val llm = FakeLlmRouterClient(
            intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
            mappingResponse = OptionMapping(pickedOptionValue = "yes", confidence = 0.95, clarifyingQuestion = null),
        )
        installApp(llm)

        client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-bp","languageCode":"ru"},"text":"болит"}""")
        }
        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-bp","languageCode":"ru"},"text":"да"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.bodyAsText()
        assertTrue(payload.contains("\"Conclusion\""), "expected a Conclusion outcome; got $payload")
        assertTrue(payload.contains("bypass_appointment"), "typed clinicAction must cross the wire; got $payload")
        assertTrue(payload.contains("emergency"), "typed urgency must cross the wire; got $payload")
        assertFalse(payload.contains("🚨"), "the urgency icon is a view artifact; got $payload")
        assertFalse(payload.contains("Записаться сейчас"), "no booking CTA on a bypass conclusion; got $payload")
    }

    @Test
    fun `no matching algorithm emits a semantic NoAlgorithmMatch, not the chrome string`() = testApplication {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "ac8-noalgo")
        val algorithmId = algorithmRepo.insert(simpleAlgorithm(), ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        installApp(
            FakeLlmRouterClient(
                intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null),
                selectionResponse = com.symptree.core.llm.AlgorithmSelection(
                    selectedId = null,
                    confidence = 0.95,
                    reason = "no match",
                ),
            ),
        )

        val body = """
            {"user":{"platform":"TELEGRAM","id":"tg-noalgo","languageCode":"ru"},"text":"слабость"}
        """.trimIndent()
        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.bodyAsText()
        assertTrue(payload.contains("\"NoAlgorithmMatch\""), "expected a NoAlgorithmMatch outcome; got $payload")
        assertFalse(payload.contains("нет сценария"), "the no-match chrome string must not cross the wire; got $payload")
    }

    // ---------- session lifecycle (unchanged behaviour, semantic wire) ----------

    @Test
    fun `slash-start mid-walk cancels the active session and re-offers Welcome`() = testApplication {
        val (clinic, _) = Fixtures.fixtureClinic(clinicRepo, slug = "start-reset")
        val algorithmId = algorithmRepo.insert(simpleAlgorithm(), ingestedBy = "test")
        clinicRepo.setAlgorithmEnabled(clinic.id, algorithmId, enabled = true)

        installApp(FakeLlmRouterClient(intentResponse = IntentClassification(Intent.SYMPTOM, 0.95, null)))

        client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-reset","languageCode":"ru"},"text":"болит голова"}""")
        }
        val midSession = sessionRepo.findActiveSession(clinic.id, "TELEGRAM", "tg-reset")
            ?: fail("expected an active session after the complaint")
        assertEquals(
            algorithmId, midSession.currentAlgorithmId,
            "first complaint must pin the algorithm for the mid-walk reset to be meaningful",
        )

        val resetResponse = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-reset","languageCode":"ru"},"text":"/start"}""")
        }
        assertEquals(HttpStatusCode.OK, resetResponse.status)
        val resetPayload = resetResponse.bodyAsText()
        assertTrue(
            resetPayload.contains("\"Welcome\"") && resetPayload.contains("symptom_consultation"),
            "`/start` after mid-walk must re-offer the Welcome outcome; got $resetPayload",
        )

        val cancelled = sessionRepo.findById(midSession.id)
            ?: fail("session row must persist after cancel")
        assertEquals("cancelled", cancelled.status, "/start must flip the mid-walk session to cancelled")
        val active = sessionRepo.findActiveSession(clinic.id, "TELEGRAM", "tg-reset")
        assertEquals(
            null, active,
            "no active session should remain after /start cancel; next message will create a new one",
        )
    }

    @Test
    fun `chosen language persists across sessions - the picker stays Kazakh after the session ends`() = testApplication {
        installApp(FakeLlmRouterClient())
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "lang-persist",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru", "kk"),
        )

        client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-lang","languageCode":"ru"},"text":"/start"}""")
        }
        client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-lang","languageCode":"ru"},"callbackData":"lang:kk"}""")
        }
        val active = sessionRepo.findActiveSession(clinic.id, "TELEGRAM", "tg-lang")
            ?: fail("expected an active session after the language switch")
        sessionRepo.cancelActive(active.id)

        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "bot_test")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-lang","languageCode":"ru"},"text":"/language"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val fresh = sessionRepo.findActiveSession(clinic.id, "TELEGRAM", "tg-lang")
            ?: fail("expected a fresh active session after /language")
        assertEquals(
            "kk", fresh.language,
            "a new session must inherit the user's last chosen language (kk), not the Telegram locale (ru)",
        )
    }

    // ---------- AC-W3WDWH.2 — shared-secret auth ----------

    @Test
    fun `AC-2 missing X-API-Key header returns 401`() = testApplication {
        installApp(FakeLlmRouterClient())
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "ac2-noauth",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru", "kk"),
        )

        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-1"},"text":"/start"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `AC-2 wrong X-API-Key value returns 401`() = testApplication {
        installApp(FakeLlmRouterClient())
        val (clinic, _) = Fixtures.fixtureClinic(
            clinicRepo,
            slug = "ac2-wrongauth",
            defaultLanguage = "ru",
            allowedLanguages = listOf("ru", "kk"),
        )

        val response = client.post("/api/v1/clinics/${clinic.slug}/bot/ingress") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "wrong-secret")
            setBody("""{"user":{"platform":"TELEGRAM","id":"tg-1"},"text":"/start"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ---------- fixtures ----------

    private fun simpleAlgorithm(): Algorithm = Algorithm(
        algorithmId = "fr_w3w_simple",
        version = "1.0.0",
        metadata = AlgorithmMetadata(
            name = mapOf("ru" to "Тест", "kk" to "Тест"),
            category = "general",
            ageGroup = "adult",
            estimatedTimeMinutes = 1,
        ),
        entryPoint = "q1",
        nodes = mapOf(
            "q1" to QuestionNode(
                id = "q1",
                text = mapOf("ru" to "Болит сейчас?", "kk" to "Қазір ауырады ма?"),
                options = listOf(
                    Option("yes", mapOf("ru" to "Да", "kk" to "Иә"), next = "c_routine"),
                    Option("no", mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "c_routine"),
                ),
            ),
            "c_routine" to ConclusionNode(
                id = "c_routine",
                urgency = Urgency.ROUTINE,
                diagnosis = mapOf("ru" to "Нужна консультация", "kk" to "Кеңес алу керек"),
                action = mapOf("ru" to "Запишитесь к терапевту", "kk" to "Терапевтке жазылыңыз"),
                specialist = "therapist",
                clinicAction = ClinicAction.BOOK_APPOINTMENT,
                followUpDays = 7,
            ),
        ),
    )

    private fun bypassConclusionAlgorithm(): Algorithm = Algorithm(
        algorithmId = "fr_w3w_bypass",
        version = "1.0.0",
        metadata = AlgorithmMetadata(
            name = mapOf("ru" to "Тест", "kk" to "Тест"),
            category = "general",
            ageGroup = "adult",
            estimatedTimeMinutes = 1,
        ),
        entryPoint = "q1",
        nodes = mapOf(
            "q1" to QuestionNode(
                id = "q1",
                text = mapOf("ru" to "Острая боль?", "kk" to "Қатты ауырады ма?"),
                options = listOf(
                    Option("yes", mapOf("ru" to "Да", "kk" to "Иә"), next = "c_emerg"),
                    Option("no", mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "c_emerg"),
                ),
            ),
            "c_emerg" to ConclusionNode(
                id = "c_emerg",
                urgency = Urgency.EMERGENCY,
                diagnosis = mapOf("ru" to "Экстренно", "kk" to "Шұғыл"),
                action = mapOf("ru" to "Вызовите 103", "kk" to "103 шақырыңыз"),
                specialist = null,
                clinicAction = ClinicAction.BYPASS_APPOINTMENT,
                followUpDays = null,
            ),
        ),
    )
}
