package com.symptree.core.scheduler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * FR-VWRNDR — POSTs a due reminder's **semantic** [ReminderPayload] to the
 * messaging module's `POST /bot-internal/outgoing`, which maps the [kind] to its
 * own catalog key, formats [slotAt] in [clinicZone], and sends it. The body is
 * the semantic `{platform_user_id, language, kind, doctor_name, slot_at,
 * clinic_zone}` shape — **no catalog key, no view format** crosses the wire. The
 * shared secret (`BOT_INTERNAL_SHARED_SECRET`, distinct from the ingress secret)
 * travels in the `X-Internal-Secret` header.
 *
 * Uses the JDK `HttpClient` (no extra dependency). A non-200 response throws, so
 * `ReminderScheduler.pollOnce` flips the row to `failed` for the next poll.
 */
class HttpReminderDispatcher(
    private val baseUrl: String,
    private val sharedSecret: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : ReminderDispatcher {

    @Serializable
    private data class OutgoingBody(
        @SerialName("platform_user_id") val platformUserId: String,
        val language: String,
        val kind: String,
        @SerialName("doctor_name") val doctorName: String,
        @SerialName("slot_at") val slotAt: Long,
        @SerialName("clinic_zone") val clinicZone: String,
    )

    override suspend fun dispatch(reminderId: Long, payload: ReminderPayload) {
        val body = json.encodeToString(
            OutgoingBody.serializer(),
            OutgoingBody(
                platformUserId = payload.platformUserId,
                language = payload.language,
                kind = payload.kind.wire,
                doctorName = payload.doctorName,
                slotAt = payload.slotAt.toEpochMilli(),
                clinicZone = payload.clinicZone,
            ),
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$OUTGOING_PATH"))
            .header("Content-Type", "application/json")
            .header("X-Internal-Secret", sharedSecret)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
        check(response.statusCode() == HTTP_OK) {
            "messaging $OUTGOING_PATH for reminder $reminderId returned ${response.statusCode()}"
        }
    }

    private companion object {
        const val OUTGOING_PATH = "/bot-internal/outgoing"
        const val HTTP_OK = 200
        val json = Json { encodeDefaults = true }
    }
}
