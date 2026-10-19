package com.symptree.messaging.port

/**
 * Platform abstraction for messaging operations.
 *
 * Adapters (TelegramAdapter, WhatsAppAdapter) implement this interface.
 * Handlers use it exclusively, never platform-specific APIs.
 */
interface MessagingPort {
    suspend fun sendText(user: PlatformUser, text: String, disableLinkPreview: Boolean = false)

    suspend fun sendPhoto(
        user: PlatformUser,
        photo: ByteArray,
        mimeType: String,
        caption: String? = null,
        options: List<MenuOption> = emptyList(),
    ): String?

    suspend fun sendMenu(
        user: PlatformUser,
        text: String,
        options: List<MenuOption>,
        layout: MenuLayout = MenuLayout.INLINE,
        disableLinkPreview: Boolean = false,
    ): String?

    suspend fun removeButtons(user: PlatformUser, messageId: String)

    suspend fun updateButtons(
        user: PlatformUser,
        messageId: String,
        options: List<MenuOption>,
        layout: MenuLayout = MenuLayout.VERTICAL,
    )

    suspend fun sendTypingIndicator(user: PlatformUser)
}
