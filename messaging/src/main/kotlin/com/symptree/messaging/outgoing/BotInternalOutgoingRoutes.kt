package com.symptree.messaging.outgoing

import com.symptree.messaging.i18n.MessageCatalog
import com.symptree.messaging.i18n.SlotFormatter
import com.symptree.messaging.port.MessagingPort
import com.symptree.messaging.port.Platform
import com.symptree.messaging.port.PlatformUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * FR-VWRNDR — inbound `POST /bot-internal/outgoing` on the messaging module.
 *
 * `core` posts a **semantic** `ReminderDue` payload —
 * `{platform_user_id, language, kind, doctor_name, slot_at, clinic_zone}` — plus
 * the shared secret in the `X-Internal-Secret` header. This route maps the
 * typed [OutgoingRequest.kind] to its **own** catalog key, formats `slot_at` in
 * `clinic_zone` (AC-VWRNDR.7), renders via the unified [MessageCatalog], and
 * dispatches through [MessagingPort.sendText]. No catalog key crosses the wire —
 * a legacy `catalog_key`-only body lacks `kind` and is rejected with 422.
 *
 *  - 401 wrong / missing secret (nothing sent),
 *  - 422 malformed body / missing `platform_user_id` or unknown/absent `kind`,
 *  - 200 on a successful render + send.
 *
 * The body is parsed manually (not via ContentNegotiation) so this route is
 * self-contained — no plugin install required by callers/tests.
 */
private val bodyJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class OutgoingRequest(
    val platform_user_id: String? = null,
    val language: String? = null,
    val kind: String? = null,
    val doctor_name: String? = null,
    val slot_at: Long? = null,
    val clinic_zone: String? = null,
)

fun Route.botInternalOutgoingRoutes(
    catalog: MessageCatalog,
    port: MessagingPort,
    sharedSecret: String,
) {
    post("/bot-internal/outgoing") {
        val presented = call.request.headers["X-Internal-Secret"]
        if (presented == null || presented != sharedSecret) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        val req = try {
            bodyJson.decodeFromString(OutgoingRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.UnprocessableEntity)
            return@post
        }

        val userId = req.platform_user_id
        val key = req.kind?.let { catalogKeyFor(it) }
        if (userId.isNullOrBlank() || key == null) {
            call.respond(HttpStatusCode.UnprocessableEntity)
            return@post
        }

        val lang = req.language ?: DEFAULT_LANG
        val params = buildMap {
            req.doctor_name?.let { put("doctor", it) }
            if (req.slot_at != null && !req.clinic_zone.isNullOrBlank()) {
                put("time", SlotFormatter.time(Instant.ofEpochMilli(req.slot_at), req.clinic_zone))
            }
        }
        val text = catalog.get(key, lang, params)
        val user = PlatformUser(platform = Platform.TELEGRAM, id = userId, languageCode = lang)
        port.sendText(user, text)
        call.respond(HttpStatusCode.OK)
    }
}

/** Map the semantic reminder [kind] to this frontend's catalog key (null = unknown). */
private fun catalogKeyFor(kind: String): String? = when (kind) {
    "pre_visit_24h" -> "bot.reminder.pre_visit_24h"
    "same_day" -> "bot.reminder.same_day"
    "post_visit_feedback" -> "bot.feedback.prompt"
    "booking_cancelled" -> "bot.booking.cancelled"
    else -> null
}

private const val DEFAULT_LANG = "ru"
