package com.symptree.core.source

/**
 * Holds a clinic's set of [MedicalDataSource]s and exposes the capability
 * providers behind them. M2 holds exactly one INTERNAL source, so these methods
 * resolve trivially against that single source; the genuine multi-source
 * federation runtime (≥2 sources of differing kinds, cross-source routing,
 * directory sync, identity dedup) lands in M3 with the second source's adapter.
 * The method shapes here are the seam M3 extends — see specs/plan/M3.md.
 */
class DataSourceRegistry(val sources: List<MedicalDataSource>) {

    /**
     * Concatenate `listDoctors` from every source whose [MedicalDataSource.directory]
     * is bound, in source order. A directory-less source contributes nothing.
     * M2: a single INTERNAL source; true multi-source federation is M3.
     */
    suspend fun listAllDoctors(clinicId: Long): List<DoctorRef> {
        val all = mutableListOf<DoctorRef>()
        for (source in sources) {
            val directory = source.directory ?: continue
            all += directory.listDoctors(clinicId)
        }
        return all
    }

    /**
     * Resolve [doctor] to the [BookingProvider] of its owning source. Returns
     * null when no source matches or the owning source has no booking binding.
     */
    fun bookingFor(doctor: DoctorRef): BookingProvider? =
        sources.firstOrNull { it.id == doctor.sourceId }?.booking
}
