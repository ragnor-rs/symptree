package com.symptree.core.llm

import com.symptree.core.symptomizer.QuestionNode

/**
 * Port for the LLM router per technical-spec.md § 3.5.
 *
 * The LLM has exactly two responsibilities in M2 (requirements.md § 2.3):
 *
 * 1. [classifyIntent] — classify the user's free-form message into one of
 *    [Intent.SYMPTOM] / [Intent.BOOKING] / [Intent.FAQ] / [Intent.FALLBACK].
 * 2. [mapFreeFormToOption] — translate a free-form reply into one of the
 *    current walker node's `option_value`s.
 *
 * Every result is validated downstream by `RouterValidator` against the
 * walker's view of legal next-state; a hallucinated option_value is rejected
 * and the user is re-prompted.
 *
 * Implementations must be safe to call from any coroutine context. The
 * M2 default is `OpenAiLlmRouterClient`; tests use `FakeLlmRouterClient`.
 */
interface LlmRouterClient {

    /**
     * Pick the best-matching algorithm for the user's free-form complaint.
     *
     * Closes the "M2 simplification: first enabled algorithm" gap in
     * `SessionService.applyPrePickStep`. The LLM is shown the list of enabled
     * algorithms (with localized names) and chooses one — or returns null
     * when no algorithm reasonably matches the complaint, so the bot can
     * surface a "we don't have a flow for that" message instead of routing
     * the user into the wrong tree.
     *
     * @param userMessage the raw user text (the symptom description).
     * @param language    BCP-47 / ISO-639 language code of [userMessage].
     * @param candidates  the clinic's enabled algorithms, in any order. Empty
     *                    list ⇒ caller should not invoke this; callers must
     *                    guarantee `candidates.isNotEmpty()`. Each carries an
     *                    already-localized [AlgorithmCandidate.displayName].
     */
    suspend fun selectAlgorithm(
        userMessage: String,
        language: String,
        candidates: List<AlgorithmCandidate>,
    ): AlgorithmSelection

    /**
     * Classify a free-form user message into one of [availableIntents].
     *
     * @param userMessage the raw user text (already PII-stripped at the
     *                    transport layer where applicable).
     * @param language    BCP-47 / ISO-639 language code of [userMessage]
     *                    (e.g., `"en"`, `"ru"`, `"kk"`).
     * @param availableIntents the set of intents the calling clinic has
     *                    enabled. The LLM must not return anything outside
     *                    this set; structured outputs enforce it.
     */
    suspend fun classifyIntent(
        userMessage: String,
        language: String,
        availableIntents: Set<Intent>,
    ): IntentClassification

    /**
     * Map a free-form yes-style user reply to one of [node]'s `option_value`s.
     *
     * @param userMessage the raw user text.
     * @param language    BCP-47 / ISO-639 language code of [userMessage].
     * @param node        the current walker [QuestionNode]; the LLM must pick
     *                    a value from `node.options.map { it.value }` or
     *                    return `null` for `pickedOptionValue` along with a
     *                    [OptionMapping.clarifyingQuestion].
     */
    suspend fun mapFreeFormToOption(
        userMessage: String,
        language: String,
        node: QuestionNode,
    ): OptionMapping
}

/**
 * Result of [LlmRouterClient.classifyIntent].
 *
 * @property intent     the classified intent.
 * @property confidence model confidence in `[0.0, 1.0]`. `RouterValidator`
 *                      rejects classifications below the per-clinic threshold
 *                      (0.6 default).
 * @property reason     optional model-supplied explanation, used in logs and
 *                      re-prompt UX when validation rejects the result.
 */
data class IntentClassification(
    val intent: Intent,
    val confidence: Double,
    val reason: String?,
)

/**
 * Result of [LlmRouterClient.mapFreeFormToOption].
 *
 * @property pickedOptionValue the chosen `option_value`, or `null` if the
 *                             model could not pick one with confidence.
 * @property confidence        model confidence in `[0.0, 1.0]`.
 * @property clarifyingQuestion when `pickedOptionValue` is `null` the model
 *                              may supply a short clarifying question for the
 *                              bot to surface instead of the raw option list.
 */
data class OptionMapping(
    val pickedOptionValue: String?,
    val confidence: Double,
    val clarifyingQuestion: String?,
)

/**
 * One row in the candidate set [LlmRouterClient.selectAlgorithm] picks from.
 *
 * @property id          the database row id of the algorithm; what the model
 *                       returns when it picks this candidate.
 * @property displayName already-localized to the caller's language (so the LLM
 *                       reasons in that language without needing the full
 *                       multilingual map).
 * @property category    body-system / specialty category from the algorithm's
 *                       metadata (e.g., "neurology", "cardiology", "ent").
 *                       Surfaces in the prompt to help the model anchor on the
 *                       body system when the user uses slang or informal body-
 *                       part references (e.g., "башка" → head → neurology).
 */
data class AlgorithmCandidate(
    val id: Long,
    val displayName: String,
    val category: String,
)

/**
 * Result of [LlmRouterClient.selectAlgorithm].
 *
 * @property selectedId  the picked algorithm id, or `null` when the model is
 *                       confident no candidate matches the user's complaint.
 *                       `RouterValidator.validateAlgorithmSelection` rejects
 *                       any non-null id outside the candidate set
 *                       (defense in depth against model-version drift).
 * @property confidence  model confidence in `[0.0, 1.0]`. Below the configured
 *                       threshold (default 0.6) the validator treats the
 *                       result as no-match regardless of [selectedId].
 * @property reason      optional model-supplied explanation for logs / debug.
 */
data class AlgorithmSelection(
    val selectedId: Long?,
    val confidence: Double,
    val reason: String?,
)
