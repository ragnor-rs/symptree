package com.symptree.core.model

/**
 * Inbound shape for inserting a clinic-scoped human user (admin panel login).
 * `passwordHash` is a bcrypt hash — the route layer hashes the plaintext before
 * it ever reaches the repo, so plaintext never crosses this boundary.
 */
data class NewClinicUser(
    val clinicId: Long,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val role: String,
    val active: Boolean,
)

/** Persistent `clinic_users` row. */
data class ClinicUser(
    val id: Long,
    val clinicId: Long,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val role: String,
    val active: Boolean,
)
