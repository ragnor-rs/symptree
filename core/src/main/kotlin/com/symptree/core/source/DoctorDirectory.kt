package com.symptree.core.source

/**
 * Capability provider: lists the doctors a source knows about for a clinic.
 *
 * Thin by design (interface segregation, technical-spec.md § 3.4) — a source may
 * implement this without implementing [SchedulingProvider] or [BookingProvider].
 */
interface DoctorDirectory {
    suspend fun listDoctors(clinicId: Long): List<DoctorRef>
}
