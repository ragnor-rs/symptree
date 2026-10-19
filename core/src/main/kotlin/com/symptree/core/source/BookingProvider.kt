package com.symptree.core.source

/** Capability provider: books and cancels appointments against a backing system. */
interface BookingProvider {
    suspend fun book(slot: Slot, patient: PatientRef): BookingResult
    suspend fun cancel(ref: ExternalApptRef)
}
