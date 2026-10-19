package com.symptree.core.auth

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Bcrypt-based hasher for clinic-admin API keys.
 *
 * Stub implementation — full unit tests live in ApiKeyHasherTest (covered by a
 * separate AC). Provides the symbol surface required by the rest of the module.
 */
object ApiKeyHasher {

    private const val COST = 10

    fun hash(key: String): String =
        BCrypt.withDefaults().hashToString(COST, key.toCharArray())

    fun verify(key: String, hash: String): Boolean = try {
        BCrypt.verifyer().verify(key.toCharArray(), hash).verified
    } catch (_: IllegalArgumentException) {
        false
    }
}
