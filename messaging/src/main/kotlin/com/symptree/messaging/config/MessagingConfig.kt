package com.symptree.messaging.config

data class MessagingConfig(
    val backendUrl: String,
    val clinicSlug: String,
    val botIngressSharedSecret: String,
    /**
     * Shared secret for the inbound `POST /bot-internal/outgoing` endpoint
     * (FR-W03362, distinct from the ingress secret). Blank ⇒ the endpoint is not
     * served (the reminder push channel is disabled); set it in production to the
     * same value as the core service's `BOT_INTERNAL_SHARED_SECRET`.
     */
    val botInternalSharedSecret: String,
    val telegramEnabled: Boolean,
    val telegramBotToken: String?,
    val whatsappEnabled: Boolean,
    val whatsappPhoneNumberId: String?,
    val whatsappAccessToken: String?,
    val whatsappVerifyToken: String?,
    val healthPort: Int,
    val maxSessions: Int,
    val sessionTimeoutHours: Int,
) {
    companion object {
        fun fromEnv(): MessagingConfig {
            val telegramToken = System.getenv("TELEGRAM_BOT_TOKEN")
            val waPhoneId = System.getenv("WHATSAPP_PHONE_NUMBER_ID")
            val waAccessToken = System.getenv("WHATSAPP_ACCESS_TOKEN")
            val clinicSlug = System.getenv("CLINIC_SLUG").orEmpty()
            check(clinicSlug.isNotBlank()) {
                "CLINIC_SLUG must be set to a non-blank value. " +
                    "Refusing to start: the bot module is single-tenant per JVM in M2 " +
                    "(one bot per clinic — per FR-W3WDWH AC.2)."
            }
            val botIngressSharedSecret = System.getenv("BOT_INGRESS_SHARED_SECRET").orEmpty()
            check(botIngressSharedSecret.isNotBlank()) {
                "BOT_INGRESS_SHARED_SECRET must be set to a non-blank value. " +
                    "Refusing to start: bot → backend ingress route requires it for auth."
            }
            return MessagingConfig(
                backendUrl = System.getenv("BACKEND_URL") ?: "http://localhost:8080",
                clinicSlug = clinicSlug,
                botIngressSharedSecret = botIngressSharedSecret,
                botInternalSharedSecret = System.getenv("BOT_INTERNAL_SHARED_SECRET").orEmpty(),
                telegramEnabled = !telegramToken.isNullOrBlank(),
                telegramBotToken = telegramToken,
                whatsappEnabled = !waPhoneId.isNullOrBlank() && !waAccessToken.isNullOrBlank(),
                whatsappPhoneNumberId = waPhoneId,
                whatsappAccessToken = waAccessToken,
                whatsappVerifyToken = System.getenv("WHATSAPP_VERIFY_TOKEN"),
                healthPort = System.getenv("PORT")?.toInt() ?: 8084,
                maxSessions = System.getenv("MAX_SESSIONS")?.toInt() ?: 1000,
                sessionTimeoutHours = System.getenv("SESSION_TIMEOUT_HOURS")?.toInt() ?: 24,
            )
        }
    }
}
