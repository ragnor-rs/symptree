package com.symptree.messaging.core

import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.User

/**
 * Builders for synthetic kotlin-telegram-bot `Update` payloads used by
 * `TelegramUpdateNormalizerTest` (AC-W3WDWH.3) and the e2e journey test
 * (AC-W3WDWH.10). Mirrors the shape Telegram's Bot API emits for `getUpdates`.
 */
internal object TelegramFixtures {

    fun textUpdate(
        chatId: Long,
        text: String,
        firstName: String = "Test",
        languageCode: String? = "ru",
        updateId: Long = 1L,
        messageId: Long = 1L,
    ): Update {
        val user = User(
            id = chatId,
            isBot = false,
            firstName = firstName,
            languageCode = languageCode,
        )
        val chat = Chat(id = chatId, type = "private")
        val message = Message(
            messageId = messageId,
            from = user,
            chat = chat,
            date = 0,
            text = text,
        )
        return Update(updateId = updateId, message = message)
    }

    fun callbackUpdate(
        chatId: Long,
        callbackData: String,
        firstName: String = "Test",
        languageCode: String? = "ru",
        updateId: Long = 1L,
        messageId: Long = 1L,
    ): Update {
        val user = User(
            id = chatId,
            isBot = false,
            firstName = firstName,
            languageCode = languageCode,
        )
        val chat = Chat(id = chatId, type = "private")
        val message = Message(
            messageId = messageId,
            from = user,
            chat = chat,
            date = 0,
            text = "",
        )
        val callback = CallbackQuery(
            id = "$updateId-cb",
            from = user,
            message = message,
            chatInstance = "$chatId",
            data = callbackData,
        )
        return Update(updateId = updateId, callbackQuery = callback)
    }
}
