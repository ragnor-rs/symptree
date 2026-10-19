package com.symptree.core.symptomizer

import kotlinx.serialization.json.JsonArray

/**
 * Bot session — one row in the `sessions` table.
 *
 * Per technical-spec.md § 2.1, a session is uniquely identified within an
 * active state by `(platform, platform_user_id, clinic_id, status)`. The
 * `history` JSONB column carries an array of per-turn records; see
 * `AC-PWVTT4.1` for the round-trip contract and `AC-PWVTT4.8` for the
 * per-turn structure.
 *
 * This data class covers the columns AC-PWVTT4.1 reads back. The mutation
 * surface for state transitions during a walker step lands in AC-PWVTT4.2/.8.
 */
data class Session(
    val id: Long,
    val clinicId: Long,
    val platform: String,
    val platformUserId: String,
    val language: String,
    val currentAlgorithmId: Long?,
    val currentNodeId: String?,
    val history: JsonArray,
    val status: String,
)
