package com.symptree.core.source.internal

import com.symptree.core.db.DoctorRepo
import com.symptree.core.model.Doctor
import com.symptree.core.source.DoctorDirectory
import com.symptree.core.source.DoctorRef
import com.symptree.core.source.SourceId

/**
 * The INTERNAL [DoctorDirectory], backed by the canonical [DoctorRepo].
 *
 * Implements ONLY [DoctorDirectory] (interface segregation) — scheduling and
 * booking for the internal source are supplied by separate providers.
 */
class InternalDoctorDirectory(private val repo: DoctorRepo) : DoctorDirectory {

    override suspend fun listDoctors(clinicId: Long): List<DoctorRef> =
        repo.listActive(clinicId).map { it.toDoctorRef() }

    private fun Doctor.toDoctorRef() = DoctorRef(
        sourceId = SourceId(sourceId),
        externalId = externalId,
        identityId = identityId,
        displayName = fullName,
        specialistCodes = specialistCodes,
    )
}
