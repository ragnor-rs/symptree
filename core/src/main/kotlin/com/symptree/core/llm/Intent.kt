package com.symptree.core.llm

/**
 * Intent classes the LLM router maps a user message into.
 *
 * Per requirements § 2.3 and technical-spec.md § 3.5, the LLM is constrained to
 * return one of these four values via structured outputs. `FALLBACK` covers
 * anything off-topic or unclassifiable; the bot surfaces a help / human-handoff
 * prompt in that case.
 */
enum class Intent {
    SYMPTOM,
    BOOKING,
    FAQ,
    FALLBACK,
}
