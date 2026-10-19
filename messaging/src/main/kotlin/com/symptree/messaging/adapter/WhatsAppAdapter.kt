package com.symptree.messaging.adapter

import com.symptree.messaging.port.MenuLayout
import com.symptree.messaging.port.MenuOption
import com.symptree.messaging.port.MessagingPort
import com.symptree.messaging.port.PlatformUser
import okhttp3.OkHttpClient

/**
 * WhatsApp adapter using Meta's Cloud API (HTTP, no Kotlin SDK).
 * Stub until /implement wires the Cloud API send/webhook flow.
 *
 * Cloud API base: https://graph.facebook.com/v21.0/{phone-number-id}/messages
 */
class WhatsAppAdapter(
    @Suppress("UnusedPrivateProperty") private val phoneNumberId: String,
    @Suppress("UnusedPrivateProperty") private val accessToken: String,
    @Suppress("UnusedPrivateProperty") private val httpClient: OkHttpClient = OkHttpClient(),
) : MessagingPort {
    override suspend fun sendText(user: PlatformUser, text: String, disableLinkPreview: Boolean) {
        TODO("WhatsApp sendText not implemented yet")
    }

    override suspend fun sendPhoto(
        user: PlatformUser,
        photo: ByteArray,
        mimeType: String,
        caption: String?,
        options: List<MenuOption>,
    ): String? {
        TODO("WhatsApp sendPhoto not implemented yet")
    }

    override suspend fun sendMenu(
        user: PlatformUser,
        text: String,
        options: List<MenuOption>,
        layout: MenuLayout,
        disableLinkPreview: Boolean,
    ): String? {
        TODO("WhatsApp sendMenu not implemented yet")
    }

    override suspend fun removeButtons(user: PlatformUser, messageId: String) {
        // WhatsApp Cloud API does not support editing messages — no-op.
    }

    override suspend fun updateButtons(
        user: PlatformUser,
        messageId: String,
        options: List<MenuOption>,
        layout: MenuLayout,
    ) {
        // WhatsApp Cloud API does not support editing messages — no-op.
    }

    override suspend fun sendTypingIndicator(user: PlatformUser) {
        // WhatsApp Cloud API has no public typing indicator — no-op.
    }
}
