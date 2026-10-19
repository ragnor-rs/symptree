package com.symptree.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Verified claims carried by an admin bearer token (FR-MG7TSR). */
data class TokenClaims(
    val clinicId: Long,
    val clinicUserId: Long,
    val role: String,
    val expiresAt: Instant,
)

/** Wire shape of the signed claims payload (base64url-encoded inside the token). */
@Serializable
private data class TokenPayload(
    @SerialName("clinic_id") val clinicId: Long,
    @SerialName("clinic_user_id") val clinicUserId: Long,
    val role: String,
    @SerialName("exp") val exp: Long,
)

/**
 * Hand-rolled HS256 signed bearer token (HMAC-SHA256 over a kotlinx-serialized
 * claims payload, base64url; no JWT library) — FR-MG7TSR.
 *
 * Format: `base64url(headerJson).base64url(claimsJson).base64url(HMAC-SHA256(secret, signingInput))`
 * where `signingInput = base64url(headerJson).base64url(claimsJson)`.
 */
class TokenService(
    private val signingSecret: String,
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun issue(
        clinicId: Long,
        clinicUserId: Long,
        role: String,
    ): String {
        val exp = clock.instant().plus(ttl).epochSecond
        val payload = TokenPayload(clinicId, clinicUserId, role, exp)
        val headerSegment = encodeSegment(HEADER_JSON.toByteArray(Charsets.UTF_8))
        val claimsJson = Json.encodeToString(TokenPayload.serializer(), payload)
        val claimsSegment = encodeSegment(claimsJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerSegment.$claimsSegment"
        val signatureSegment = encodeSegment(sign(signingInput))
        return "$signingInput.$signatureSegment"
    }

    fun verify(token: String): TokenClaims? {
        val parts = token.split(".")
        if (parts.size != EXPECTED_PARTS) return null
        val (headerSegment, claimsSegment, signatureSegment) = parts
        if (headerSegment.isEmpty() || claimsSegment.isEmpty() || signatureSegment.isEmpty()) return null

        val signingInput = "$headerSegment.$claimsSegment"
        val expected = sign(signingInput)
        val provided = try {
            decoder.decode(signatureSegment)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Constant-time compare — never short-circuit on the first differing byte.
        if (!MessageDigest.isEqual(expected, provided)) return null

        val payload = try {
            val claimsBytes = decoder.decode(claimsSegment)
            Json.decodeFromString(TokenPayload.serializer(), String(claimsBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }

        val expiresAt = Instant.ofEpochSecond(payload.exp)
        // exp <= now ⇒ expired.
        if (!expiresAt.isAfter(clock.instant())) return null

        return TokenClaims(
            clinicId = payload.clinicId,
            clinicUserId = payload.clinicUserId,
            role = payload.role,
            expiresAt = expiresAt,
        )
    }

    private fun encodeSegment(bytes: ByteArray): String = encoder.encodeToString(bytes)

    private fun sign(signingInput: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(signingInput.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val HEADER_JSON = """{"alg":"HS256","typ":"JWT"}"""
        const val EXPECTED_PARTS = 3
    }
}
