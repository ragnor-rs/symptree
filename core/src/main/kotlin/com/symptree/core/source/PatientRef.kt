package com.symptree.core.source

/** Source-agnostic reference to the patient a [BookingProvider] is booking for. */
data class PatientRef(
    val externalId: String?,
    val displayName: String?,
    val phone: String?,
    val languageCode: String,
)
