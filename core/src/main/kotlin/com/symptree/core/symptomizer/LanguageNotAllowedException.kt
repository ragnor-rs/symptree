package com.symptree.core.symptomizer

/**
 * Thrown by `SessionService.setLanguage` when the caller asks to switch to a
 * language code that is not in the clinic's `allowed_languages` list.
 *
 * Per AC-PWVTT4.3 the language override is rejected at the service layer so
 * the bot adapter can surface a localized "language not supported" message.
 *
 * @property language the rejected language code (e.g., `"tr"`).
 * @property allowed  the clinic's `allowed_languages` set, included so the
 *                    error message and any UX can list legal alternatives.
 */
class LanguageNotAllowedException(
    val language: String,
    val allowed: List<String>,
) : RuntimeException(
    "language '$language' not in clinic allowed_languages $allowed",
)
