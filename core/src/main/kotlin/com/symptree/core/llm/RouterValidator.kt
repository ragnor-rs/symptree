package com.symptree.core.llm

import com.symptree.core.symptomizer.QuestionNode

/**
 * Validates LLM router outputs against the walker's view of legal next-state
 * and the per-clinic confidence threshold.
 *
 * Per technical-spec.md § 3.5 and requirements § 2.3:
 *
 * - [validateIntent] accepts an [IntentClassification] only when the picked
 *   [Intent] is in the clinic's enabled set AND the model's confidence meets
 *   the configured threshold (default 0.6, overridable via
 *   `clinics.config.llm.confidence_threshold`).
 * - [validateOption] (AC-PWVTT4.7) accepts an [OptionMapping] only when the
 *   picked `option_value` is one of the current [QuestionNode]'s options AND
 *   confidence meets the threshold.
 *
 * Rejections are surfaced via [LlmRejectedException] with a structured
 * [LlmRejectedException.Reason] so callers can drive the re-prompt UX.
 */
object RouterValidator {

    /**
     * Validate an intent classification.
     *
     * @param classification the LLM's classification result.
     * @param allowed        the set of intents the calling clinic has enabled.
     * @param confidenceThreshold minimum acceptable confidence in `[0.0, 1.0]`
     *                            (default 0.6).
     * @return the picked [Intent] when accepted.
     * @throws LlmRejectedException with [LlmRejectedException.Reason.OUT_OF_SET]
     *         when the intent is not in [allowed], or with
     *         [LlmRejectedException.Reason.LOW_CONFIDENCE] when confidence is
     *         below [confidenceThreshold].
     */
    fun validateIntent(
        classification: IntentClassification,
        allowed: Set<Intent>,
        confidenceThreshold: Double = 0.6,
    ): Intent {
        if (classification.intent !in allowed) {
            throw LlmRejectedException(
                reason = LlmRejectedException.Reason.OUT_OF_SET,
                detail = "intent ${classification.intent} not in allowed set $allowed",
            )
        }
        if (classification.confidence < confidenceThreshold) {
            throw LlmRejectedException(
                reason = LlmRejectedException.Reason.LOW_CONFIDENCE,
                detail = "confidence ${classification.confidence} < threshold $confidenceThreshold",
            )
        }
        return classification.intent
    }

    /**
     * Validate a free-form → option mapping.
     *
     * @param mapping the LLM's option-mapping result.
     * @param node    the current [QuestionNode] whose `options` define the
     *                allowed `option_value` set.
     * @param confidenceThreshold minimum acceptable confidence in `[0.0, 1.0]`
     *                            (default 0.6).
     * @return the picked `option_value` when accepted.
     * @throws LlmRejectedException with [LlmRejectedException.Reason.NULL_PICK]
     *         when `mapping.pickedOptionValue` is null; with
     *         [LlmRejectedException.Reason.LOW_CONFIDENCE] when confidence is
     *         below [confidenceThreshold]; or with
     *         [LlmRejectedException.Reason.OUT_OF_SET] when the picked value is
     *         not one of [node]'s options.
     */
    fun validateOption(
        mapping: OptionMapping,
        node: QuestionNode,
        confidenceThreshold: Double = 0.6,
    ): String {
        val picked = mapping.pickedOptionValue
            ?: throw LlmRejectedException(
                reason = LlmRejectedException.Reason.NULL_PICK,
                detail = "model returned null pick" +
                    (mapping.clarifyingQuestion?.let { " (clarifyingQuestion=\"$it\")" } ?: ""),
            )
        if (mapping.confidence < confidenceThreshold) {
            throw LlmRejectedException(
                reason = LlmRejectedException.Reason.LOW_CONFIDENCE,
                detail = "confidence ${mapping.confidence} < threshold $confidenceThreshold",
            )
        }
        val allowedValues = node.options.map { it.value }
        if (picked !in allowedValues) {
            throw LlmRejectedException(
                reason = LlmRejectedException.Reason.OUT_OF_SET,
                detail = "option_value \"$picked\" not in node \"${node.id}\" options $allowedValues",
            )
        }
        return picked
    }

    /**
     * Validate an algorithm-selection result.
     *
     * Rules:
     *  - `selection.selectedId == null` ⇒ no match (the LLM said so explicitly);
     *    return null. Confidence threshold doesn't apply — "none" is a valid
     *    answer.
     *  - `selection.selectedId != null` ⇒ id MUST be one of [candidateIds] AND
     *    `confidence >= confidenceThreshold`. Otherwise treat as no match
     *    (returns null) — the bot surfaces the same "we don't have a flow for
     *    that" message rather than routing to a possibly-wrong algorithm.
     *
     * @return the validated id when accepted, or `null` when the result is
     *         the explicit no-match OR fails the validation guards.
     */
    fun validateAlgorithmSelection(
        selection: AlgorithmSelection,
        candidateIds: Set<Long>,
        confidenceThreshold: Double = 0.6,
    ): Long? {
        val id = selection.selectedId ?: return null
        if (id !in candidateIds) return null
        if (selection.confidence < confidenceThreshold) return null
        return id
    }
}
