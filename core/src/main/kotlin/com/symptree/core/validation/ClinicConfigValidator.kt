package com.symptree.core.validation

import com.symptree.core.model.CreateClinicRequest

/**
 * Result of validating a [CreateClinicRequest] body.
 *
 * Returned by [ClinicConfigValidator.validate]; consumed by `POST /api/v1/clinics`.
 */
sealed class ValidationResult {
    data object Ok : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

/**
 * Validates the inbound body of `POST /api/v1/clinics`.
 *
 * Rules (per FR M3WYNS Technical Design + AC-M3WYNS.2):
 *  - `slug` must match `^[a-z0-9-]{3,64}$`
 *  - `name` must be non-blank
 *  - `default_language` must be one of [SUPPORTED_LANGUAGES]
 *  - `allowed_languages` must be a non-empty subset of [SUPPORTED_LANGUAGES]
 *  - `default_language` must appear in `allowed_languages`
 *  - `admin_email`, when supplied (FR-PRVSN2), must match a minimal e-mail shape
 */
object ClinicConfigValidator {

    private val SUPPORTED_LANGUAGES = setOf("ru", "kk", "tr", "uz", "kg", "ka")
    private val SLUG_REGEX = Regex("^[a-z0-9-]{3,64}$")

    // Minimal e-mail shape check — one local part, one @, a dotted domain. The
    // authoritative validation is deferred to delivery; this only blocks obvious
    // garbage before a clinic row (and a login) is provisioned.
    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun validate(request: CreateClinicRequest): ValidationResult {
        val errors = mutableListOf<String>()

        if (!SLUG_REGEX.matches(request.slug)) {
            errors += "slug must match ^[a-z0-9-]{3,64}$ (got '${request.slug}')"
        }

        if (request.name.isBlank()) {
            errors += "name must be non-blank"
        }

        if (request.allowedLanguages.isEmpty()) {
            errors += "allowed_languages must be a non-empty list"
        }

        val unsupportedAllowed = request.allowedLanguages.filterNot { it in SUPPORTED_LANGUAGES }
        if (unsupportedAllowed.isNotEmpty()) {
            errors += "allowed_languages contains unsupported codes: $unsupportedAllowed " +
                "(supported: $SUPPORTED_LANGUAGES)"
        }

        if (request.defaultLanguage !in SUPPORTED_LANGUAGES) {
            errors += "default_language '${request.defaultLanguage}' is not a supported language code " +
                "(supported: $SUPPORTED_LANGUAGES)"
        } else if (request.defaultLanguage !in request.allowedLanguages) {
            errors += "default_language '${request.defaultLanguage}' must be present in allowed_languages " +
                "${request.allowedLanguages}"
        }

        // AC-PRVSN2.5: an invalid admin_email fails the same validation pass,
        // before any insert, so a bad address never half-provisions a clinic.
        val adminEmail = request.adminEmail
        if (adminEmail != null && !EMAIL_REGEX.matches(adminEmail)) {
            errors += "admin_email is not a valid e-mail address"
        }

        return if (errors.isEmpty()) ValidationResult.Ok else ValidationResult.Invalid(errors)
    }
}
