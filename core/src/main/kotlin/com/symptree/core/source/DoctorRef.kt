package com.symptree.core.source

/**
 * Source-agnostic reference to a doctor as surfaced by a [DoctorDirectory].
 *
 * [specialistCodes] are free-form strings matched against `ConclusionNode.specialist`
 * (no new enum) per technical-spec.md § 3.4.
 */
data class DoctorRef(
    val sourceId: SourceId,
    val externalId: String,
    val identityId: Long,
    val displayName: String,
    val specialistCodes: List<String>,
)
