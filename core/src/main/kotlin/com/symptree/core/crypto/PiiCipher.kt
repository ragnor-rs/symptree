package com.symptree.core.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM application-layer cipher for PII at rest in `sessions.history`.
 *
 * Per AC-PWVTT4.10 / requirements § 2.5:
 *   - Key length: exactly 32 bytes (AES-256). Wrong length is rejected at
 *     construction with [IllegalArgumentException].
 *   - Encryption: AES/GCM/NoPadding with a fresh, randomly generated 12-byte
 *     IV per call (via [SecureRandom]) and the GCM default 128-bit auth tag.
 *   - Envelope (on-the-wire): `[12-byte IV][ciphertext+auth tag]`, Base64-encoded.
 *   - Decryption: parses the envelope and runs AES/GCM/NoPadding in DECRYPT mode.
 *     Tampered ciphertext or wrong key surfaces as [javax.crypto.AEADBadTagException].
 *
 * The key is supplied from env `SYMPTREE_PII_KEY` at process startup; only the
 * retention/anonymization job and the (M3+) admin export-my-data endpoint
 * ever instantiate a `PiiCipher` for decryption.
 */
class PiiCipher(key: ByteArray) {

    init {
        require(key.size == KEY_BYTES) {
            "PII key must be exactly $KEY_BYTES bytes (256 bits) for AES-256-GCM; got ${key.size} bytes"
        }
    }

    private val secretKey: SecretKeySpec = SecretKeySpec(key.copyOf(), "AES")
    private val random: SecureRandom = SecureRandom()

    /**
     * Encrypts [plaintext] (UTF-8) under AES-256-GCM with a fresh random IV.
     * Returns a Base64 string of `[12-byte IV][ciphertext+auth tag]`.
     */
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val envelope = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, envelope, 0, iv.size)
        System.arraycopy(ct, 0, envelope, iv.size, ct.size)
        return Base64.getEncoder().encodeToString(envelope)
    }

    /**
     * Decrypts a Base64 envelope produced by [encrypt]. Throws
     * [javax.crypto.AEADBadTagException] (or a related Cipher exception)
     * if the auth tag fails to verify (wrong key, tampered bytes).
     */
    fun decrypt(ciphertextBase64: String): String {
        val envelope = Base64.getDecoder().decode(ciphertextBase64)
        require(envelope.size > IV_BYTES) {
            "ciphertext envelope too short: expected > $IV_BYTES bytes, got ${envelope.size}"
        }
        val iv = envelope.copyOfRange(0, IV_BYTES)
        val ct = envelope.copyOfRange(IV_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        val pt = cipher.doFinal(ct)
        return String(pt, Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
