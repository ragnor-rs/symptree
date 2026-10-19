package com.symptree.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApiKeyHasherTest {

    @Test
    fun `verify accepts the original key against its hash`() {
        val key = "ck_live_8f9d3e2a1b4c5d6e7f8901234567890ab"
        val hash = ApiKeyHasher.hash(key)
        assertTrue(ApiKeyHasher.verify(key, hash),
            "hasher should verify the original key")
    }

    @Test
    fun `verify rejects a wrong key`() {
        val key = "ck_live_correct"
        val hash = ApiKeyHasher.hash(key)
        assertFalse(ApiKeyHasher.verify("ck_live_wrong", hash),
            "hasher should reject a different key")
    }

    @Test
    fun `verify rejects empty key against valid hash`() {
        val hash = ApiKeyHasher.hash("ck_live_correct")
        assertFalse(ApiKeyHasher.verify("", hash))
    }

    @Test
    fun `hash is not the plaintext key`() {
        val key = "ck_live_secret"
        val hash = ApiKeyHasher.hash(key)
        assertNotEquals(key, hash, "hash must not equal plaintext")
        assertFalse(hash.contains(key), "hash must not contain plaintext")
    }

    @Test
    fun `hash returns different output each time due to salt`() {
        val key = "ck_live_salt_check"
        val h1 = ApiKeyHasher.hash(key)
        val h2 = ApiKeyHasher.hash(key)
        assertNotEquals(h1, h2, "bcrypt salts must vary between invocations")
        assertTrue(ApiKeyHasher.verify(key, h1))
        assertTrue(ApiKeyHasher.verify(key, h2))
    }

    @Test
    fun `verify returns false for malformed hash without throwing`() {
        assertFalse(ApiKeyHasher.verify("any-key", "not-a-bcrypt-hash"))
    }
}
