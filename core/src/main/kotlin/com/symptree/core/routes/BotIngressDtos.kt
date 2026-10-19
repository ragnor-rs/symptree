package com.symptree.core.routes

import kotlinx.serialization.Serializable

/**
 * Inbound wire DTOs for `POST /api/v1/clinics/{slug}/bot/ingress` — AC-W3WDWH.2.
 *
 * Mirrors the shape of `messaging/.../port/IncomingMessage`. The two modules
 * don't share code (no compile-time dep per CLAUDE.md); they share only the wire
 * contract. The **response** shape lives in [IngressOutcomeDto] (FR-VWRNDR
 * semantic outcome model).
 */

@Serializable
internal data class IngressRequest(
    val user: PlatformUserDto,
    val text: String? = null,
    val callbackData: String? = null,
)

@Serializable
internal data class PlatformUserDto(
    val platform: String,
    val id: String,
    val displayName: String? = null,
    val languageCode: String? = null,
)
