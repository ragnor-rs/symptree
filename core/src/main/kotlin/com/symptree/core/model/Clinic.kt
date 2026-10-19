package com.symptree.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Persistent clinic record.
 *
 * `config` is a JSONB blob whose shape is documented in technical-spec.md § 2.1.
 * The `api_key_hash` for clinic-admin authentication lives inside this object.
 */
@Serializable
data class Clinic(
    val id: Long,
    val slug: String,
    val name: String,
    @SerialName("default_language") val defaultLanguage: String,
    @SerialName("allowed_languages") val allowedLanguages: List<String>,
    val config: JsonObject,
    @SerialName("is_active") val isActive: Boolean,
)

/**
 * Inbound JSON shape for `POST /api/v1/clinics`. Wire field names are
 * snake_case per technical-spec.md § 3.1; the Kotlin properties are camelCase
 * per the language convention. The mapping is via `@SerialName`.
 */
@Serializable
data class CreateClinicRequest(
    val slug: String,
    val name: String,
    @SerialName("default_language") val defaultLanguage: String,
    @SerialName("allowed_languages") val allowedLanguages: List<String>,
    val config: JsonObject = JsonObject(emptyMap()),
    @SerialName("admin_email") val adminEmail: String? = null,
)

/**
 * Outbound JSON shape for `POST /api/v1/clinics`.
 *
 * The plaintext clinic-admin API key is returned exactly once at creation
 * time; subsequent reads only expose its bcrypt hash inside the config blob,
 * and even that is stripped before serialization — see
 * `routes/ClinicRoutes.kt` `withoutApiKeyHash()`.
 */
@Serializable
data class CreateClinicResponse(
    val clinic: Clinic,
    @SerialName("clinic_admin_api_key") val clinicAdminApiKey: String,
    val admin: SeededAdmin? = null,
)

/**
 * One-time clinic-admin login block, present in the create response only when an
 * `admin_email` was supplied. The `temp_password` is returned exactly once and is
 * never persisted in plaintext (only its bcrypt hash lands in `clinic_users`).
 */
@Serializable
data class SeededAdmin(
    val email: String,
    @SerialName("temp_password") val tempPassword: String,
)

/**
 * Inbound JSON shape for `PUT /api/v1/clinics/{slug}/config`.
 *
 * `configPatch` is deep-merged into the existing clinic config; see
 * `routes/ClinicRoutes.kt` `deepMergeJsonObjects` for semantics.
 */
@Serializable
data class UpdateConfigRequest(
    val configPatch: JsonObject,
)
