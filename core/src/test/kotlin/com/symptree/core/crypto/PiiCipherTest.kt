package com.symptree.core.crypto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-PWVTT4.10 — PII at the application layer: AES-256-GCM via `PiiCipher`.
 *
 * Round-trip with a deterministic test key:
 *   - encrypt(plaintext) → ciphertext (Base64)
 *   - decrypt(ciphertext) → plaintext
 *
 * Ciphertext is non-deterministic across calls because the IV (nonce) is
 * randomized per encryption — same plaintext + same key produces different
 * ciphertext on each call.
 *
 * Key validation: AES-256 requires a 256-bit key. Anything else is rejected
 * at construction time with `IllegalArgumentException`.
 */
class PiiCipherTest {

    private val testKey: ByteArray = ByteArray(32) { it.toByte() } // 32 zero-ish bytes = 256 bits

    @Test
    fun `AC-10 encrypt then decrypt round-trips the plaintext`() {
        val cipher = PiiCipher(testKey)
        val plaintext = "у меня сильно болит голова с утра"
        val ciphertext = cipher.encrypt(plaintext)
        val decoded = cipher.decrypt(ciphertext)
        assertEquals(plaintext, decoded)
    }

    @Test
    fun `AC-10 two encryptions of the same plaintext produce different ciphertext (IV randomization)`() {
        val cipher = PiiCipher(testKey)
        val plaintext = "the same message twice"
        val c1 = cipher.encrypt(plaintext)
        val c2 = cipher.encrypt(plaintext)
        assertTrue(
            c1 != c2,
            "IV randomization must produce different ciphertext for same plaintext (got $c1 == $c2)",
        )
        // Both must still round-trip:
        assertEquals(plaintext, cipher.decrypt(c1))
        assertEquals(plaintext, cipher.decrypt(c2))
    }

    @Test
    fun `AC-10 encrypt returns Base64 text`() {
        val cipher = PiiCipher(testKey)
        val ciphertext = cipher.encrypt("hello")
        // Base64 alphabet: A-Z a-z 0-9 + / = ; assert nothing else leaks.
        assertTrue(
            ciphertext.matches(Regex("^[A-Za-z0-9+/]+={0,2}$")),
            "ciphertext must be Base64-encoded; got: $ciphertext",
        )
    }

    @Test
    fun `AC-10 constructor rejects keys that are not 32 bytes`() {
        try {
            PiiCipher(ByteArray(16) { it.toByte() })
            fail("expected IllegalArgumentException for non-256-bit key")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                e.message?.contains("32") == true || e.message?.contains("256") == true,
                "expected error to reference key length 32 bytes / 256 bits; got: ${e.message}",
            )
        }
    }

    @Test
    fun `AC-10 decrypting with a different key fails (typed exception)`() {
        val cipher = PiiCipher(testKey)
        val tampered = PiiCipher(ByteArray(32) { (it + 1).toByte() })
        val ciphertext = cipher.encrypt("secret payload")
        try {
            tampered.decrypt(ciphertext)
            fail("expected decryption to fail with wrong key")
        } catch (e: Exception) {
            // AES-GCM authentication-tag mismatch surfaces as some Cipher exception.
            assertTrue(
                e is javax.crypto.AEADBadTagException ||
                    e is javax.crypto.BadPaddingException ||
                    e is IllegalStateException,
                "expected Cipher-related failure; got ${e::class.simpleName}: ${e.message}",
            )
        }
    }
}
