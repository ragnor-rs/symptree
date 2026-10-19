package com.symptree.messaging.client

import com.symptree.messaging.port.IncomingMessage
import com.symptree.messaging.port.IngressResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger("com.symptree.messaging.client.CoreClient")

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * HTTP client for talking to the core.
 *
 * `open` so test code can swap an in-memory stub for [botIngress] without
 * standing up a real HTTP server.
 */
open class CoreClient(
    private val baseUrl: String,
    private val apiKey: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun health(): Boolean {
        val request = Request.Builder().url("$baseUrl/health").get().build()
        return httpClient.newCall(request).execute().use { it.isSuccessful }
    }

    /**
     * AC-W3WDWH.2 / FR-VWRNDR — POST a normalized [IncomingMessage] to
     * `POST /api/v1/clinics/{slug}/bot/ingress` and return the backend's
     * **semantic** [IngressResponse] (resolved session language + a list of typed
     * outcome variants). The caller renders these via `IngressRenderer`; the wire
     * carries no chrome strings.
     *
     * Photos are deliberately not yet wired through this surface — AC-W3WDWH.2
     * scope is text + callback only.
     */
    open suspend fun botIngress(clinicSlug: String, incoming: IncomingMessage): IngressResponse =
        withContext(Dispatchers.IO) {
            val payload = IngressRequestDto(
                user = PlatformUserDto(
                    platform = incoming.user.platform.name,
                    id = incoming.user.id,
                    displayName = incoming.user.displayName,
                    languageCode = incoming.user.languageCode,
                ),
                text = incoming.text,
                callbackData = incoming.callbackData,
            )
            val body = json.encodeToString(IngressRequestDto.serializer(), payload)
            val builder = Request.Builder()
                .url("$baseUrl/api/v1/clinics/$clinicSlug/bot/ingress")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
            apiKey?.let { builder.addHeader("X-API-Key", it) }
            val response = httpClient.newCall(builder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    logger.error("bot ingress failed: {} - {}", resp.code, resp.body?.string())
                    return@withContext IngressResponse(DEFAULT_LANG, emptyList())
                }
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return@withContext IngressResponse(DEFAULT_LANG, emptyList())
                try {
                    json.decodeFromString(IngressResponse.serializer(), text)
                } catch (e: Exception) {
                    logger.error("bot ingress decode failed: {}", e.message, e)
                    IngressResponse(DEFAULT_LANG, emptyList())
                }
            }
        }

    private companion object {
        const val DEFAULT_LANG = "ru"
    }
}

@Serializable
private data class IngressRequestDto(
    val user: PlatformUserDto,
    val text: String? = null,
    val callbackData: String? = null,
)

@Serializable
private data class PlatformUserDto(
    val platform: String,
    val id: String,
    val displayName: String? = null,
    val languageCode: String? = null,
)

