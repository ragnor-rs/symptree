package com.symptree.messaging.port

data class PlatformUser(
    val platform: Platform,
    val id: String,
    val displayName: String? = null,
    val languageCode: String? = null,
)
