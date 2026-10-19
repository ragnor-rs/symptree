package com.symptree.core.llm

import com.symptree.core.symptomizer.Option
import com.symptree.core.symptomizer.QuestionNode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-PWVTT4.5 — `OpenAiLlmRouterClient` is the M2 default impl backed by
 * OpenAI Chat Completions with structured outputs. Test layer uses
 * MockWebServer + recorded JSON fixtures under
 * `core/src/test/resources/openai/` so we never hit the real API in
 * `./gradlew test`.
 *
 * The implementer wires the OpenAI Java SDK (`com.openai:openai-java-*`)
 * with a custom base URL pointed at the mock server. We assert that:
 *   - the structured-output JSON the SDK returns is mapped into our
 *     `IntentClassification` / `OptionMapping` DTOs intact,
 *   - the request body sent to OpenAI carries a `response_format` of type
 *     `json_schema` whose schema's `intent` enum (resp. `option_value`
 *     enum) constrains the model to the legal value set.
 *
 * `OPENAI_API_KEY` fail-fast at startup is asserted by
 * `OpenAiLlmRouterClientConfigTest`.
 */
class OpenAiLlmRouterClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `AC-5 classifyIntent maps OpenAI structured-output response into IntentClassification`() {
        val fixture = readFixture("intent_symptom_high_confidence.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val client = OpenAiLlmRouterClient(
            apiKey = "test",
            baseUrl = server.url("/v1").toString(),
        )
        val result = runBlocking {
            client.classifyIntent(
                userMessage = "у меня болит голова",
                language = "ru",
                availableIntents = setOf(Intent.SYMPTOM, Intent.BOOKING, Intent.FAQ, Intent.FALLBACK),
            )
        }
        assertEquals(Intent.SYMPTOM, result.intent)
        assertTrue(result.confidence >= 0.9, "expected high confidence, got ${result.confidence}")

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        // Defense-in-depth: structured-output schema must mention the Intent enum values.
        assertTrue(body.contains("json_schema"), "request must request a JSON-schema response_format; got: $body")
        for (intent in Intent.entries) {
            assertTrue(
                body.contains(intent.name) || body.contains(intent.name.lowercase()),
                "request schema must list intent ${intent.name}; got: $body",
            )
        }
    }

    @Test
    fun `AC-5 mapFreeFormToOption maps OpenAI structured-output response into OptionMapping with node option enum`() {
        val fixture = readFixture("option_yes_high_confidence.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val node = QuestionNode(
            id = "q1",
            text = mapOf("ru" to "Внезапно?", "kk" to "Күтпеген?"),
            options = listOf(
                Option(value = "yes", label = mapOf("ru" to "Да", "kk" to "Иә"), next = "c1"),
                Option(value = "no", label = mapOf("ru" to "Нет", "kk" to "Жоқ"), next = "q2"),
            ),
        )

        val client = OpenAiLlmRouterClient(apiKey = "test", baseUrl = server.url("/v1").toString())
        val result = runBlocking {
            client.mapFreeFormToOption(userMessage = "да, очень внезапно", language = "ru", node = node)
        }
        assertEquals("yes", result.pickedOptionValue)
        assertTrue(result.confidence >= 0.6)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("yes"), "request schema must enumerate option 'yes'; got: $body")
        assertTrue(body.contains("no"), "request schema must enumerate option 'no'; got: $body")
    }

    @Test
    fun `AC-5 OpenAiLlmRouterClient implements the LlmRouterClient port`() {
        val client = OpenAiLlmRouterClient(apiKey = "test", baseUrl = server.url("/v1").toString())
        // Compile-time + runtime assertion of the port relationship.
        val asPort: LlmRouterClient = client
        @Suppress("USELESS_IS_CHECK")
        assertTrue(asPort is LlmRouterClient)
    }

    private fun readFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/openai/$name")
            ?: error("missing test fixture: /openai/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
