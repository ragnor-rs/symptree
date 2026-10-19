package com.symptree.core.model

/**
 * Inbound shape for inserting/upserting a doctor into the canonical catalog.
 *
 * `sourceId` + `externalId` form the upsert key. `specialistCodes` are free-form
 * strings matched against `ConclusionNode.specialist` (no enum).
 */
data class NewDoctor(
    val clinicId: Long,
    val sourceId: String,
    val externalId: String,
    val fullName: String,
    val specialistCodes: List<String>,
    val slotMinutes: Int,
    val active: Boolean,
)

/**
 * Persistent doctor record from the `doctors` table, joined to its singleton
 * `doctor_identity` via [identityId]. The identity survives re-upserts so that
 * downstream references (sessions, bookings) stay stable across syncs.
 */
data class Doctor(
    val id: Long,
    val clinicId: Long,
    val identityId: Long,
    val sourceId: String,
    val externalId: String,
    val fullName: String,
    val specialistCodes: List<String>,
    val slotMinutes: Int,
    val active: Boolean,
)
