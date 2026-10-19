package com.symptree.messaging.port

import kotlinx.serialization.Serializable

@Serializable
data class MenuOption(
    val id: String,
    val label: String,
)

@Serializable
enum class MenuLayout {
    INLINE,
    VERTICAL,
}
