package com.symptree.core.scheduler

import com.symptree.core.routes.admin.BookingCancelNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

/**
 * FR-MG7TSR (AC-MG7TSR.7) — production [BookingCancelNotifier] that POSTs a
 * **semantic** `booking_cancelled` payload to the messaging module's
 * `POST /bot-internal/outgoing`, which maps the `kind` to its own catalog key
 * (`bot.booking.cancelled`), formats `slot_at` in `clinic_zone`, and sends it.
 *
 * The body is the same semantic `{platform_user_id, language, kind, doctor_name,
 * slot_at, clinic_zone}` shape the reminder path uses (FR-VWRNDR) — no catalog
 * key, no view format crosses the wire. The shared secret
 * (`BOT_INTERNAL_SHARED_SECRET`) travels in the `X-Internal-Secret` header.
 *
 * The clinic's scheduling zone is resolved per-call via [zoneFor] (the same
 * `clinics.config.preferences.timezone` lookup the booking path uses), since the
 * [BookingCancelNotifier] contract carries only the clinic id. A non-200 response
 * throws — the admin cancel route logs/propagates rather than silently dropping.
 */
class HttpBookingCancelNotifier(
    private val baseUrl: String,
    private val sharedSecret: String,
    private val zoneFor: (Long) -> String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : BookingCancelNotifier {

    @Serializable
    private data class OutgoingBody(
        @SerialName("platform_user_id") val platformUserId: String,
        val language: String,
        val kind: String,
        @SerialName("doctor_name") val doctorName: String,
        @SerialName("slot_at") val slotAt: Long,
        @SerialName("clinic_zone") val clinicZone: String,
    )

    override suspend fun bookingCancelled(
        clinicId: Long,
        platformUserId: String,
        language: String,
        doctorName: String,
        slotAt: Instant,
    ) {
        val body = json.encodeToString(
            OutgoingBody.serializer(),
            OutgoingBody(
                platformUserId = platformUserId,
                language = language,
                kind = KIND,
                doctorName = doctorName,
                slotAt = slotAt.toEpochMilli(),
                clinicZone = zoneFor(clinicId),
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
            "messaging $OUTGOING_PATH for booking-cancelled returned ${response.statusCode()}"
        }
    }

    private companion object {
        const val OUTGOING_PATH = "/bot-internal/outgoing"
        const val KIND = "booking_cancelled"
        const val HTTP_OK = 200
        val json = Json { encodeDefaults = true }
    }
}
