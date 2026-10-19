package com.symptree.core.llm

import com.symptree.core.symptomizer.QuestionNode

/**
 * Hand-rolled fake for [LlmRouterClient] per testing-spec.md § 5.5.
 *
 * Returns canned [IntentClassification] / [OptionMapping] responses by default;
 * tests override per-call by passing different constructor arguments.
 *
 * If [throwOnCall] is true, both methods throw the supplied exception (or a
 * default RuntimeException) — used by AC-PWVTT4.9 to exercise the
 * `ButtonOnlyPrompt` fallback path.
 *
 * Records the number of times each method was invoked so tests can assert
 * "the LLM was not retried within a single request" (AC-PWVTT4.9).
 */
class FakeLlmRouterClient(
    private val intentResponse: IntentClassification = IntentClassification(Intent.SYMPTOM, 0.95, null),
    private val mappingResponse: OptionMapping = OptionMapping(
        pickedOptionValue = "yes",
        confidence = 0.9,
        clarifyingQuestion = null,
    ),
    /**
     * If non-null, `selectAlgorithm` returns this exact result. Otherwise the
     * fake picks the first candidate with high confidence — matches the
     * historical "first enabled algorithm" simplification so tests written
     * before LLM-driven selection still pass.
     */
    private val selectionResponse: AlgorithmSelection? = null,
    private val throwOnCall: Throwable? = null,
) : LlmRouterClient {

    var classifyIntentCallCount: Int = 0
        private set
    var mapFreeFormToOptionCallCount: Int = 0
        private set
    var selectAlgorithmCallCount: Int = 0
        private set

    override suspend fun selectAlgorithm(
        userMessage: String,
        language: String,
        candidates: List<AlgorithmCandidate>,
    ): AlgorithmSelection {
        selectAlgorithmCallCount += 1
        throwOnCall?.let { throw it }
        selectionResponse?.let { return it }
        // Default: pick the first candidate with high confidence (M2-simplification-equivalent).
        return AlgorithmSelection(
            selectedId = candidates.firstOrNull()?.id,
            confidence = 0.9,
            reason = null,
        )
    }

    override suspend fun classifyIntent(
        userMessage: String,
        language: String,
        availableIntents: Set<Intent>,
    ): IntentClassification {
        classifyIntentCallCount += 1
        throwOnCall?.let { throw it }
        return intentResponse
    }

    override suspend fun mapFreeFormToOption(
        userMessage: String,
        language: String,
        node: QuestionNode,
    ): OptionMapping {
        mapFreeFormToOptionCallCount += 1
        throwOnCall?.let { throw it }
        return mappingResponse
    }
}
