package com.symptree.core.source

import java.time.Instant

/** A bookable time window for a [DoctorRef]. */
data class Slot(
    val doctorRef: DoctorRef,
    val start: Instant,
    val end: Instant,
)
