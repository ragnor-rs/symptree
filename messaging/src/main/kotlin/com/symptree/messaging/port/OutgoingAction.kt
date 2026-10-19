package com.symptree.messaging.port

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Backend → bot output payload. The bot module replays each entry through its
 * [MessagingPort] adapter on the platform of the inbound user.
 *
 * Wire shape: kotlinx-serialization polymorphic JSON with default `"type"`
 * discriminator. Each variant's `@SerialName` must match the corresponding
 * `OutgoingActionDto` variant on the backend (`core/.../routes/BotIngressDtos.kt`)
 * byte-exactly — the two modules don't share code; they share only the wire
 * contract.
 */
@Serializable
sealed interface OutgoingAction {

    @Serializable
    @SerialName("SendText")
    data class SendText(
        val text: String,
        val disableLinkPreview: Boolean = false,
    ) : OutgoingAction

    @Serializable
    @SerialName("SendMenu")
    data class SendMenu(
        val text: String,
        val options: List<MenuOption>,
        val layout: MenuLayout = MenuLayout.INLINE,
        val disableLinkPreview: Boolean = false,
    ) : OutgoingAction

    @Serializable
    @SerialName("SendPhoto")
    data class SendPhoto(
        val photoBytes: ByteArray,
        val mimeType: String,
        val caption: String? = null,
        val options: List<MenuOption> = emptyList(),
    ) : OutgoingAction {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendPhoto) return false
            return photoBytes.contentEquals(other.photoBytes) &&
                mimeType == other.mimeType &&
                caption == other.caption &&
                options == other.options
        }
        override fun hashCode(): Int {
            var result = photoBytes.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + (caption?.hashCode() ?: 0)
            result = 31 * result + options.hashCode()
            return result
        }
    }

    @Serializable
    @SerialName("SendTyping")
    data class SendTyping(val marker: Boolean = true) : OutgoingAction

    @Serializable
    @SerialName("RemoveButtons")
    data class RemoveButtons(val messageId: String) : OutgoingAction

    @Serializable
    @SerialName("UpdateButtons")
    data class UpdateButtons(
        val messageId: String,
        val options: List<MenuOption>,
        val layout: MenuLayout = MenuLayout.INLINE,
    ) : OutgoingAction
}
