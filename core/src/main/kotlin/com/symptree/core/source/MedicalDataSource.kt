package com.symptree.core.source

/**
 * A composition of capability providers backing one of a clinic's medical-data
 * sources. Any capability binding may be null (e.g. a directory-less source
 * contributes no doctors to the federation).
 */
class MedicalDataSource(
    val id: SourceId,
    val kind: SourceKind,
    val directory: DoctorDirectory?,
    val scheduling: SchedulingProvider?,
    val booking: BookingProvider?,
)
