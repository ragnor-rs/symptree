package com.symptree.core.source

/**
 * Opaque external reference to a booked appointment, as returned by a
 * [BookingProvider]. Null for internal bookings (see [BookingResult.externalRef]).
 */
@JvmInline
value class ExternalApptRef(val value: String)
