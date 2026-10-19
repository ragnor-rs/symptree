package com.symptree.core.validation

import com.symptree.core.model.CreateClinicRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClinicConfigValidatorTest {

    private fun req(
        slug: String = "clinic-alpha",
        name: String = "Alpha Clinic",
        defaultLanguage: String = "ru",
        allowedLanguages: List<String> = listOf("ru", "kk"),
        config: JsonObject = JsonObject(emptyMap()),
    ) = CreateClinicRequest(
        slug = slug,
        name = name,
        defaultLanguage = defaultLanguage,
        allowedLanguages = allowedLanguages,
        config = config,
    )

    @Test
    fun `valid request passes`() {
        val result = ClinicConfigValidator.validate(req())
        assertEquals(ValidationResult.Ok, result)
    }

    @Test
    fun `valid request with kk default and ru kk allowed passes`() {
        val r = req(defaultLanguage = "kk", allowedLanguages = listOf("ru", "kk"))
        assertEquals(ValidationResult.Ok, ClinicConfigValidator.validate(r))
    }

    @Test
    fun `slug too short fails`() {
        val r = req(slug = "ab")
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid, "expected Invalid but got $result")
        assertTrue(result.errors.any { it.contains("slug", ignoreCase = true) },
            "expected slug error in $result")
    }

    @Test
    fun `slug too long fails`() {
        val r = req(slug = "a".repeat(65))
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue(result.errors.any { it.contains("slug", ignoreCase = true) })
    }

    @Test
    fun `slug with uppercase fails`() {
        val r = req(slug = "Clinic-A")
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue(result.errors.any { it.contains("slug", ignoreCase = true) })
    }

    @Test
    fun `slug with underscore fails`() {
        val r = req(slug = "clinic_a")
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `slug with space fails`() {
        val r = req(slug = "clinic a")
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `slug min length 3 passes`() {
        val r = req(slug = "abc")
        assertEquals(ValidationResult.Ok, ClinicConfigValidator.validate(r))
    }

    @Test
    fun `slug max length 64 passes`() {
        val r = req(slug = "a".repeat(64))
        assertEquals(ValidationResult.Ok, ClinicConfigValidator.validate(r))
    }

    @Test
    fun `default language must be in allowed languages`() {
        val r = req(defaultLanguage = "tr", allowedLanguages = listOf("ru", "kk"))
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue(result.errors.any { it.contains("default_language", ignoreCase = true) })
    }

    @Test
    fun `unknown default language fails`() {
        val r = req(defaultLanguage = "fr", allowedLanguages = listOf("fr"))
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `unknown allowed language fails`() {
        val r = req(defaultLanguage = "ru", allowedLanguages = listOf("ru", "fr"))
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue(result.errors.any { it.contains("allowed_languages", ignoreCase = true) })
    }

    @Test
    fun `empty allowed languages fails`() {
        val r = req(defaultLanguage = "ru", allowedLanguages = emptyList())
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `blank name fails`() {
        val r = req(name = "")
        val result = ClinicConfigValidator.validate(r)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue(result.errors.any { it.contains("name", ignoreCase = true) })
    }

    @Test
    fun `all six supported language codes are accepted`() {
        for (code in listOf("ru", "kk", "tr", "uz", "kg", "ka")) {
            val r = req(defaultLanguage = code, allowedLanguages = listOf(code))
            assertEquals(ValidationResult.Ok, ClinicConfigValidator.validate(r),
                "language code $code should be accepted")
        }
    }

    @Test
    fun `config with arbitrary keys is allowed`() {
        val cfg = buildJsonObject {
            put("specialists", JsonPrimitive("ignored"))
        }
        val r = req(config = cfg)
        assertEquals(ValidationResult.Ok, ClinicConfigValidator.validate(r))
    }
}
