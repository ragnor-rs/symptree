package com.symptree.messaging.i18n

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * FR-VWRNDR / AC-VWRNDR.7 — **view formatting is frontend-only.** `core` sends a
 * raw `slotAt: Instant` + `clinicZone`; this formats the clinic-local wall-clock
 * string. An unparseable zone falls back to UTC (mirrors the old `core` behavior).
 */
object SlotFormatter {
    private val DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    private fun zoneOf(zone: String): ZoneId =
        runCatching { ZoneId.of(zone) }.getOrDefault(ZoneOffset.UTC)

    /** `dd.MM HH:mm` clinic-local — the ingress booking `{datetime}` token. */
    fun datetime(instant: Instant, zone: String): String =
        DATETIME.withZone(zoneOf(zone)).format(instant)

    /** `HH:mm` clinic-local — the reminder `{time}` token. */
    fun time(instant: Instant, zone: String): String =
        TIME.withZone(zoneOf(zone)).format(instant)
}
