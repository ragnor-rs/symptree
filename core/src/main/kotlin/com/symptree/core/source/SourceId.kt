package com.symptree.core.source

/**
 * Opaque identifier for a [MedicalDataSource] within a clinic's source set.
 *
 * Source-agnostic per technical-spec.md § 3.4 — carries no provider-type name.
 */
@JvmInline
value class SourceId(val value: String)
