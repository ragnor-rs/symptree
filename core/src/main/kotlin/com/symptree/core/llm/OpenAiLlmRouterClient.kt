package com.symptree.core.llm

import com.symptree.core.symptomizer.QuestionNode
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AC-PWVTT4.5 — M2 default `LlmRouterClient` impl backed by OpenAI Chat
 * Completions with **structured outputs** (JSON Schema). The schema is
 * constructed per-call so the model literally cannot return:
 *  - an `intent` outside the supplied [Intent] subset, or
 *  - a `picked_option_value` outside the current node's `option_value` set.
 *
 * Defense-in-depth: `RouterValidator` (AC.6/AC.7) still re-checks every
 * response against the legal next-state — see `RouterValidator.kt`.
 *
 * Exceptions thrown by the underlying SDK (network, timeout, 5xx) propagate;
 * `SessionService.applyStep` (AC.9) is the layer that catches them and falls
 * back to button-only mode.
 *
 * @property openAiClient configured OpenAI Java SDK client.
 * @property model        Chat Completions model name. Defaults to a 2024-08-06
 *                        snapshot supporting Structured Outputs.
 */
class OpenAiLlmRouterClient(
    private val openAiClient: OpenAIClient,
    private val model: String = DEFAULT_MODEL,
) : LlmRouterClient {

    /**
     * Convenience constructor that wires an [OpenAIClient] over OkHttp from an
     * API key + base URL — used by tests against `MockWebServer` and by
     * `CoreApp.main` against the real OpenAI API.
     */
    constructor(
        apiKey: String,
        baseUrl: String = "https://api.openai.com/v1",
        model: String = DEFAULT_MODEL,
    ) : this(
        openAiClient = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .build(),
        model = model,
    )

    override suspend fun selectAlgorithm(
        userMessage: String,
        language: String,
        candidates: List<AlgorithmCandidate>,
    ): AlgorithmSelection {
        require(candidates.isNotEmpty()) {
            "selectAlgorithm requires at least one candidate; callers must guard against empty enabled-algorithm sets"
        }
        // Strict-mode Structured Outputs: enum carries the candidate ids as
        // strings (the JSON Schema "enum" expects atoms) plus the literal
        // "none" sentinel for the no-match case. Defense-in-depth validation
        // re-checks the returned id against the candidate set in
        // RouterValidator.validateAlgorithmSelection.
        val candidateIdStrings = candidates.map { it.id.toString() }
        val schemaProps: Map<String, Any?> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("selected_id", "confidence", "reason"),
            "properties" to mapOf(
                "selected_id" to mapOf(
                    "type" to "string",
                    "enum" to candidateIdStrings + listOf(NONE_SENTINEL),
                ),
                "confidence" to mapOf("type" to "number"),
                "reason" to mapOf("type" to listOf("string", "null")),
            ),
        )
        val responseFormat = buildResponseFormat(
            name = "algorithm_selection",
            schemaProps = schemaProps,
        )
        val systemPrompt = buildString {
            append("You route a user's symptom complaint to the best-matching triage flow.\n\n")
            append("Pick a flow's numeric id ONLY when the user's message is a complaint that ")
            append("directly describes a symptom, body part, body system, or anatomical region ")
            append("covered by that flow. Within a genuine complaint, be generous with informal ")
            append("language, slang, regional variants, colloquialisms, diminutives, misspellings, ")
            append("and anatomical synonyms in the user's own language — a short body-part or ")
            append("symptom word in the user's register (a colloquial term for a splitting ")
            append("headache maps to a head/neurology flow) IS a match.\n\n")
            append("Return \"").append(NONE_SENTINEL).append("\" whenever the message is not a ")
            append("direct symptom of an enabled flow. This includes: a complaint about a body ")
            append("part or system NOT covered by any listed flow (e.g. back/spine when no ")
            append("back/spine flow is enabled); a vague lifestyle, emotional, or general-health ")
            append("word that is not itself a symptom of a listed flow (e.g. sleep, tiredness, ")
            append("fatigue, weakness, stress — in any language); and greetings, questions, or ")
            append("anything off-topic. Do NOT stretch an indirect or ")
            append("speculative association (e.g. poor sleep might cause migraines) into a match — ")
            append("when in doubt, return \"").append(NONE_SENTINEL).append("\". A wrong triage flow ")
            append("is worse than asking the patient to describe their symptom directly.\n\n")
            append("Available flows:\n")
            for (c in candidates) {
                append("  - id=").append(c.id)
                    .append(": ").append(c.displayName)
                    .append(" [category: ").append(c.category).append("]\n")
            }
            append("\nRespond ONLY in the JSON schema you were given. ")
            append("User language: ").append(language).append('.')
        }
        val params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(model))
            .addSystemMessage(systemPrompt)
            .addUserMessage(userMessage)
            .responseFormat(responseFormat)
            .build()

        val raw = callOpenAi(params)
        val dto = jsonParser.decodeFromString<AlgorithmSelectionDto>(raw)
        val selectedId = if (dto.selectedId == NONE_SENTINEL) null else dto.selectedId.toLongOrNull()
        return AlgorithmSelection(
            selectedId = selectedId,
            confidence = dto.confidence,
            reason = dto.reason,
        )
    }

    override suspend fun classifyIntent(
        userMessage: String,
        language: String,
        availableIntents: Set<Intent>,
    ): IntentClassification {
        val intentEnum = availableIntents.sortedBy { it.name }.map { it.name }
        val schemaProps: Map<String, Any?> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("intent", "confidence", "reason"),
            "properties" to mapOf(
                "intent" to mapOf(
                    "type" to "string",
                    "enum" to intentEnum,
                ),
                "confidence" to mapOf("type" to "number"),
                "reason" to mapOf("type" to listOf("string", "null")),
            ),
        )
        val responseFormat = buildResponseFormat(
            name = "intent_classification",
            schemaProps = schemaProps,
        )
        val systemPrompt = buildString {
            append("You are a triage router. Classify the user's intent into exactly one of: ")
            append(availableIntents.joinToString(", ") { it.name })
            append(". Respond ONLY in the JSON schema you were given. ")
            append("User language: ").append(language).append('.')
        }
        val params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(model))
            .addSystemMessage(systemPrompt)
            .addUserMessage(userMessage)
            .responseFormat(responseFormat)
            .build()

        val raw = callOpenAi(params)
        val dto = jsonParser.decodeFromString<IntentClassificationDto>(raw)
        return IntentClassification(
            intent = Intent.valueOf(dto.intent.uppercase()),
            confidence = dto.confidence,
            reason = dto.reason,
        )
    }

    override suspend fun mapFreeFormToOption(
        userMessage: String,
        language: String,
        node: QuestionNode,
    ): OptionMapping {
        val optionValues = node.options.map { it.value }
        // OpenAI Structured Outputs (strict: true) requires every property in
        // `properties` to be listed in `required`. Nullable types are expressed
        // as a JSON array on `type` ("string"/"null").
        val schemaProps: Map<String, Any?> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("picked_option_value", "confidence", "clarifying_question"),
            "properties" to mapOf(
                "picked_option_value" to mapOf(
                    "type" to listOf("string", "null"),
                    "enum" to optionValues + listOf<String?>(null),
                ),
                "confidence" to mapOf("type" to "number"),
                "clarifying_question" to mapOf("type" to listOf("string", "null")),
            ),
        )
        val responseFormat = buildResponseFormat(
            name = "option_mapping",
            schemaProps = schemaProps,
        )
        val questionText = node.text[language] ?: node.text.values.firstOrNull().orEmpty()
        val systemPrompt = buildString {
            append("You map a user's free-form answer to one of the option values for the question: ")
            append(questionText)
            append(". Available option values: ")
            append(optionValues.joinToString(", "))
            append(". If unsure, set picked_option_value to null and provide clarifying_question. ")
            append("Respond ONLY in the JSON schema you were given. ")
            append("User language: ").append(language).append('.')
        }
        val params = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(model))
            .addSystemMessage(systemPrompt)
            .addUserMessage(userMessage)
            .responseFormat(responseFormat)
            .build()

        val raw = callOpenAi(params)
        val dto = jsonParser.decodeFromString<OptionMappingDto>(raw)
        return OptionMapping(
            pickedOptionValue = dto.pickedOptionValue,
            confidence = dto.confidence,
            clarifyingQuestion = dto.clarifyingQuestion,
        )
    }

    private suspend fun callOpenAi(params: ChatCompletionCreateParams): String =
        withContext(Dispatchers.IO) {
            val completion = openAiClient.chat().completions().create(params)
            val choice = completion.choices().firstOrNull()
                ?: error("OpenAI returned an empty choices array")
            val content = choice.message().content().orElse("")
            // Empty content means the model finished with a non-content reason
            // (refusal, length cutoff, tool_call) — those are real upstream errors,
            // not a JSON parse problem; surface a typed error so SessionService.applyStep
            // falls back to button-only via the same path it uses for network 5xx.
            check(content.isNotBlank()) {
                "OpenAI returned a blank content field (finish_reason=${choice.finishReason()}); " +
                    "Structured Outputs expected — refusing to parse"
            }
            content
        }

    /**
     * Build a strict-mode `response_format: { type: "json_schema", json_schema: {...} }`
     * envelope. The inner `schema` body carries the actual JSON-Schema
     * document via Jackson-serialized additional properties.
     */
    private fun buildResponseFormat(
        name: String,
        schemaProps: Map<String, Any?>,
    ): ResponseFormatJsonSchema {
        val schemaBuilder = ResponseFormatJsonSchema.JsonSchema.Schema.builder()
        for ((key, value) in schemaProps) {
            schemaBuilder.putAdditionalProperty(key, JsonValue.from(value))
        }
        val schemaObj = schemaBuilder.build()
        val jsonSchemaEnvelope = ResponseFormatJsonSchema.JsonSchema.builder()
            .name(name)
            .strict(true)
            .schema(schemaObj)
            .build()
        return ResponseFormatJsonSchema.builder()
            .jsonSchema(jsonSchemaEnvelope)
            .build()
    }

    @Serializable
    private data class AlgorithmSelectionDto(
        @SerialName("selected_id") val selectedId: String,
        val confidence: Double,
        val reason: String? = null,
    )

    @Serializable
    private data class IntentClassificationDto(
        val intent: String,
        val confidence: Double,
        val reason: String? = null,
    )

    @Serializable
    private data class OptionMappingDto(
        @SerialName("picked_option_value") val pickedOptionValue: String? = null,
        val confidence: Double,
        @SerialName("clarifying_question") val clarifyingQuestion: String? = null,
    )

    companion object {
        const val DEFAULT_MODEL: String = "gpt-4o-2024-08-06"
        private const val NONE_SENTINEL: String = "none"

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
