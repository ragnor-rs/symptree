package com.symptree.core.source

/**
 * Outcome of a [BookingProvider.book] call.
 *
 * [externalRef] is the booking's reference in the backing system, or null for
 * internal bookings (which have no external system).
 */
data class BookingResult(
    val externalRef: String?,
    val confirmedSlot: Slot,
)
