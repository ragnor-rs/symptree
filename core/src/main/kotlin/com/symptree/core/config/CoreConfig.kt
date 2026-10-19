package com.symptree.core.config

import java.util.Base64

data class CoreConfig(
    val port: Int,
    val postgresHost: String,
    val postgresPort: Int,
    val postgresDb: String,
    val postgresUser: String,
    val postgresPassword: String,
    val platformAdminApiKey: String,
    val botIngressSharedSecret: String,
    /** Base URL of the messaging module's internal HTTP server (AC-W03362.8/.9). */
    val botInternalBaseUrl: String,
    /** Shared secret for `POST /bot-internal/outgoing` — distinct from the ingress secret. */
    val botInternalSharedSecret: String,
    val openAiApiKey: String,
    val openAiModel: String,
    val piiKey: String,
    /** HMAC-SHA256 signing secret for admin-panel bearer tokens (FR-MG7TSR / AC-MG7TSR.2). */
    val adminTokenSecret: String,
    /** Admin bearer-token TTL in minutes (FR-MG7TSR / AC-MG7TSR.2); default 8h. */
    val adminTokenTtlMinutes: Long,
) {

    /**
     * Decode [piiKey] (raw 32 bytes UTF-8 OR Base64-encoded 32 bytes) into the
     * canonical 32-byte AES-256 key for `PiiCipher`. `fromEnv` already validated
     * the format, so this never throws at runtime in production.
     */
    fun piiKeyBytes(): ByteArray {
        val raw = piiKey.toByteArray(Charsets.UTF_8)
        if (raw.size == PII_KEY_BYTES) return raw
        return Base64.getDecoder().decode(piiKey).also { decoded ->
            check(decoded.size == PII_KEY_BYTES) {
                "SYMPTREE_PII_KEY decoded to ${decoded.size} bytes; expected $PII_KEY_BYTES (AES-256)"
            }
        }
    }

    companion object {
        private const val PII_KEY_BYTES = 32

        /** Default admin bearer-token TTL: 8 hours, expressed in minutes. */
        private const val DEFAULT_ADMIN_TOKEN_TTL_MINUTES = 480L

        // Default OpenAI Chat Completions model snapshot supporting Structured Outputs.
        // Override at deploy time via env `OPENAI_MODEL` when migrating to a newer snapshot.
        const val DEFAULT_OPENAI_MODEL: String = "gpt-4o-2024-08-06"

        fun fromEnv(): CoreConfig {
            val platformAdminApiKey = System.getenv("PLATFORM_ADMIN_API_KEY").orEmpty()
            check(platformAdminApiKey.isNotBlank()) {
                "PLATFORM_ADMIN_API_KEY must be set to a non-blank value. " +
                    "Refusing to start: without it, the clinic-onboarding endpoint " +
                    "(POST /api/v1/clinics) is permanently inaccessible."
            }
            val botIngressSharedSecret = System.getenv("BOT_INGRESS_SHARED_SECRET").orEmpty()
            check(botIngressSharedSecret.isNotBlank()) {
                "BOT_INGRESS_SHARED_SECRET must be set to a non-blank value. " +
                    "Refusing to start: bot → backend ingress route " +
                    "(POST /api/v1/clinics/{slug}/bot/ingress) requires it for auth."
            }
            val botInternalSharedSecret = System.getenv("BOT_INTERNAL_SHARED_SECRET").orEmpty()
            check(botInternalSharedSecret.isNotBlank()) {
                "BOT_INTERNAL_SHARED_SECRET must be set to a non-blank value. " +
                    "Refusing to start: the reminder scheduler posts to the messaging module's " +
                    "POST /bot-internal/outgoing (FR-W03362) and authenticates with this secret."
            }
            val openAiApiKey = System.getenv("OPENAI_API_KEY").orEmpty()
            check(openAiApiKey.isNotBlank()) {
                "OPENAI_API_KEY must be set to a non-blank value. " +
                    "Refusing to start: the symptomizer's LLM router (FR-PWVTT4) " +
                    "cannot function without it."
            }
            val piiKey = System.getenv("SYMPTREE_PII_KEY").orEmpty()
            check(piiKey.isNotBlank()) {
                "SYMPTREE_PII_KEY must be set to a non-blank value. " +
                    "Refusing to start: PII encryption (AES-256-GCM) requires a key."
            }
            check(isValidPiiKey(piiKey)) {
                "SYMPTREE_PII_KEY must decode to exactly $PII_KEY_BYTES bytes for AES-256-GCM " +
                    "(either $PII_KEY_BYTES raw bytes or a Base64-encoded 32-byte key)."
            }
            val adminTokenSecret = System.getenv("ADMIN_TOKEN_SECRET").orEmpty()
            check(adminTokenSecret.isNotBlank()) {
                "ADMIN_TOKEN_SECRET must be set to a non-blank value. " +
                    "Refusing to start: the clinic-admin panel login (POST /api/v1/admin/login, " +
                    "FR-MG7TSR) signs bearer tokens with it (HMAC-SHA256)."
            }
            val postgresPassword = System.getenv("POSTGRES_PASSWORD").orEmpty()
            check(postgresPassword.isNotBlank()) {
                "POSTGRES_PASSWORD must be set to a non-blank value. " +
                    "Refusing to start: there is deliberately no default, so a missing var fails " +
                    "loudly here rather than falling back to a known-weak password."
            }
            return CoreConfig(
                port = System.getenv("PORT")?.toInt() ?: 8080,
                postgresHost = System.getenv("POSTGRES_HOST") ?: "localhost",
                postgresPort = System.getenv("POSTGRES_PORT")?.toInt() ?: 5432,
                postgresDb = System.getenv("POSTGRES_DB") ?: "symptree",
                postgresUser = System.getenv("POSTGRES_USER") ?: "symptree_user",
                postgresPassword = postgresPassword,
                platformAdminApiKey = platformAdminApiKey,
                botIngressSharedSecret = botIngressSharedSecret,
                botInternalBaseUrl = System.getenv("BOT_INTERNAL_BASE_URL")?.takeIf { it.isNotBlank() }
                    ?: "http://localhost:8084",
                botInternalSharedSecret = botInternalSharedSecret,
                openAiApiKey = openAiApiKey,
                openAiModel = System.getenv("OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_OPENAI_MODEL,
                piiKey = piiKey,
                adminTokenSecret = adminTokenSecret,
                adminTokenTtlMinutes = System.getenv("ADMIN_TOKEN_TTL_MINUTES")?.toLong()
                    ?: DEFAULT_ADMIN_TOKEN_TTL_MINUTES,
            )
        }

        private fun isValidPiiKey(piiKey: String): Boolean {
            if (piiKey.toByteArray(Charsets.UTF_8).size == PII_KEY_BYTES) {
                return true
            }
            return try {
                Base64.getDecoder().decode(piiKey).size == PII_KEY_BYTES
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}
