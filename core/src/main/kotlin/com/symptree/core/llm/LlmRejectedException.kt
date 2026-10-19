package com.symptree.core.llm

/**
 * Thrown by `RouterValidator` when an LLM result fails validation against the
 * walker's view of legal next-state or the per-clinic confidence threshold.
 *
 * Per technical-spec.md § 3.5 and requirements § 2.3, the LLM has exactly two
 * narrow roles in M2: intent classification and free-form → option mapping.
 * Both outputs are validated downstream and rejected with a structured
 * [Reason] when out-of-bounds. The bot surfaces the rejection by re-prompting
 * the user (e.g., showing the option list verbatim or a clarifying question).
 *
 * @property reason structured rejection cause for logs and re-prompt UX.
 * @property detail optional human-readable detail (model-supplied or
 *                  caller-supplied) — never user PII.
 */
class LlmRejectedException(
    val reason: Reason,
    val detail: String? = null,
) : RuntimeException(buildMessage(reason, detail)) {

    /**
     * Structured rejection cause.
     *
     * - [OUT_OF_SET] — the picked value is not in the allowed set (intent
     *   not in the clinic's enabled set, or option_value not in the current
     *   node's options).
     * - [LOW_CONFIDENCE] — the model's confidence is below the configured
     *   threshold (default 0.6, overridable per-clinic via
     *   `clinics.config.llm.confidence_threshold`).
     * - [NULL_PICK] — the model explicitly returned a null pick (only legal
     *   for the option-mapping path with a `clarifyingQuestion`).
     * - [NON_SYMPTOM_INTENT] — the model classified a valid non-SYMPTOM
     *   intent (BOOKING / FAQ / FALLBACK) — not a rejection of LLM correctness
     *   but a routing signal that the symptomizer is the wrong destination.
     *   Distinct from [OUT_OF_SET] so the bot can surface
     *   "want a booking?" / "want a service-info FAQ?" differently from a
     *   reprompt for a hallucinated value.
     */
    enum class Reason { OUT_OF_SET, LOW_CONFIDENCE, NULL_PICK, NON_SYMPTOM_INTENT }

    companion object {
        private fun buildMessage(reason: Reason, detail: String?): String =
            if (detail.isNullOrBlank()) "LLM result rejected: $reason"
            else "LLM result rejected: $reason — $detail"
    }
}
