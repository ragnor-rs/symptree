package com.symptree.core.auth

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-MG7TSR.2 — hand-rolled HS256 signed bearer token. `TokenService.issue`
 * mints a base64url token carrying clinic_id + clinic_user_id + role + expiry;
 * `verify` validates the HMAC-SHA256 signature and expiry and returns the claims
 * (or null on tamper / expiry / wrong secret). No JWT library.
 */
class TokenServiceTest {

    private val now = Instant.parse("2026-06-12T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ttl = Duration.ofHours(8)

    private fun service(secret: String = "test-signing-secret", clock: Clock = this.clock): TokenService =
        TokenService(signingSecret = secret, ttl = ttl, clock = clock)

    @Test
    fun `issue then verify round-trips the claims`() {
        val svc = service()
        val token = svc.issue(clinicId = 42L, clinicUserId = 7L, role = "admin")

        val claims = svc.verify(token) ?: error("freshly issued token must verify")
        assertEquals(42L, claims.clinicId)
        assertEquals(7L, claims.clinicUserId)
        assertEquals("admin", claims.role)
    }

    @Test
    fun `token expiry is now plus the configured TTL`() {
        val svc = service()
        val token = svc.issue(clinicId = 1L, clinicUserId = 1L, role = "operator")
        val claims = svc.verify(token) ?: error("token must verify")
        assertEquals(now.plus(ttl).epochSecond, claims.expiresAt.epochSecond,
            "expiry must be issue-time + TTL")
    }

    @Test
    fun `verify returns null for a token signed with a different secret`() {
        val issuer = service(secret = "secret-A")
        val token = issuer.issue(clinicId = 1L, clinicUserId = 1L, role = "admin")

        val verifier = service(secret = "secret-B")
        assertNull(verifier.verify(token),
            "a token signed with a different secret must not verify")
    }

    @Test
    fun `verify returns null for a tampered payload`() {
        val svc = service()
        val token = svc.issue(clinicId = 1L, clinicUserId = 1L, role = "operator")
        // Flip the very first character of the token body to corrupt the signed payload.
        val tampered = (if (token[0] == 'A') 'B' else 'A') + token.substring(1)
        assertNull(svc.verify(tampered), "a tampered token must not verify")
    }

    @Test
    fun `verify returns null for an expired token`() {
        val issuer = service()
        val token = issuer.issue(clinicId = 1L, clinicUserId = 1L, role = "admin")

        // A verifier whose clock is past issue-time + TTL must reject the token.
        val laterClock = Clock.fixed(now.plus(ttl).plusSeconds(1), ZoneOffset.UTC)
        val expiredVerifier = service(clock = laterClock)
        assertNull(expiredVerifier.verify(token), "an expired token must not verify")
    }

    @Test
    fun `verify returns null for a structurally garbage token`() {
        val svc = service()
        assertNull(svc.verify("not-a-real-token"))
        assertNull(svc.verify(""))
    }

    @Test
    fun `issued token is opaque - it does not leak the role in plaintext`() {
        // The payload is base64url, not plaintext; a substring scan for the role
        // word must miss (defends against accidentally emitting a readable claim).
        val svc = service()
        val token = svc.issue(clinicId = 1L, clinicUserId = 1L, role = "admin")
        assertNotNull(token)
        assertTrue(token.isNotBlank())
        assertFalse(token.contains("\"role\""), "raw JSON claim must not appear verbatim in the token")
    }
}
