package com.symptree.core.source

import java.time.Instant

/** Capability provider: computes available [Slot]s for a doctor within a window. */
interface SchedulingProvider {
    suspend fun availability(doctor: DoctorRef, window: ClosedRange<Instant>): List<Slot>
}
