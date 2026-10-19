package com.symptree.messaging.core

import com.symptree.messaging.port.IncomingMessage
import com.symptree.messaging.port.Platform
import com.symptree.messaging.port.PlatformUser
import com.github.kotlintelegrambot.entities.Update

/**
 * AC-W3WDWH.3 — normalize a kotlin-telegram-bot [Update] into an
 * [IncomingMessage], carrying the user's `language_code` forward as
 * the `PlatformUser.languageCode` hint.
 *
 * The bot just surfaces the raw `language_code` from Telegram; the
 * backend's `SessionService.startOrResume` decides whether to accept
 * it (allowed_languages) or fall back to the clinic's default_language.
 */
object TelegramUpdateNormalizer {
    fun normalize(update: Update): IncomingMessage? {
        val callback = update.callbackQuery
        if (callback != null) {
            val from = callback.from
            val chatId = callback.message?.chat?.id ?: from.id
            val user = PlatformUser(
                platform = Platform.TELEGRAM,
                id = chatId.toString(),
                displayName = from.firstName,
                languageCode = from.languageCode,
            )
            return IncomingMessage(
                user = user,
                text = null,
                callbackData = callback.data,
            )
        }

        val message = update.message ?: return null
        val from = message.from ?: return null
        val user = PlatformUser(
            platform = Platform.TELEGRAM,
            id = message.chat.id.toString(),
            displayName = from.firstName,
            languageCode = from.languageCode,
        )
        return IncomingMessage(
            user = user,
            text = message.text,
        )
    }
}
