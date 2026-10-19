package com.symptree.messaging.adapter

import com.symptree.messaging.port.MenuLayout
import com.symptree.messaging.port.MenuOption
import com.symptree.messaging.port.MessagingPort
import com.symptree.messaging.port.PlatformUser
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TelegramAdapter")

private const val INLINE_ROW_SIZE = 3

/**
 * Telegram implementation of [MessagingPort], backed by the `kotlin-telegram-bot`
 * library. Converts platform-agnostic operations into Bot API calls.
 *
 * The [bot] instance is constructed externally (typically in `MessagingMain`)
 * with the production Telegram URL, or by tests with a MockWebServer URL —
 * mirrors the canonical MMP-sibling pattern (`../MMP/dressmibot/adapter/TelegramAdapter.kt`).
 */
class TelegramAdapter(
    private val bot: Bot,
) : MessagingPort {

    override suspend fun sendText(user: PlatformUser, text: String, disableLinkPreview: Boolean) {
        withContext(Dispatchers.IO) {
            val chatId = user.id.toLongOrNull()
            if (chatId == null) {
                logger.error("Invalid Telegram user ID: ${user.id}")
                return@withContext
            }
            // No parseMode — i18n catalog strings are plain text. The
            // markdown-with-fallback pattern (MMP-ported) had a duplicate-send
            // bug: when the lib's response parser returned null on a send that
            // had succeeded on the wire, the fallback would fire a second send
            // and Telegram delivered both messages. Drop the fallback; reinstate
            // parseMode once we have a use case that needs it.
            try {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = text,
                    disableWebPagePreview = disableLinkPreview,
                )
            } catch (e: Exception) {
                logger.warn("Telegram sendText failed: ${e.message}")
            }
        }
    }

    override suspend fun sendPhoto(
        user: PlatformUser,
        photo: ByteArray,
        mimeType: String,
        caption: String?,
        options: List<MenuOption>,
    ): String? = withContext(Dispatchers.IO) {
        val chatId = user.id.toLongOrNull()
        if (chatId == null) {
            logger.error("Invalid Telegram user ID: ${user.id}")
            return@withContext null
        }

        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val keyboard = if (options.isNotEmpty()) inlineKeyboard(options) else null

        try {
            val result = bot.sendPhoto(
                chatId = ChatId.fromId(chatId),
                photo = TelegramFile.ByByteArray(photo, "photo.$extension"),
                caption = caption,
                parseMode = if (caption != null) ParseMode.MARKDOWN else null,
                replyMarkup = keyboard,
            )
            result.first?.body()?.result?.messageId?.toString()
        } catch (e: Exception) {
            logger.error("Telegram sendPhoto failed: ${e.message}", e)
            null
        }
    }

    override suspend fun sendMenu(
        user: PlatformUser,
        text: String,
        options: List<MenuOption>,
        layout: MenuLayout,
        disableLinkPreview: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val chatId = user.id.toLongOrNull()
        if (chatId == null) {
            logger.error("Invalid Telegram user ID: ${user.id}")
            return@withContext null
        }

        val keyboard = keyboardForLayout(options, layout)

        // Single send, no markdown + fallback. The prior pattern
        // `primary ?: bot.sendMessage(...)` fired a second wire-send whenever
        // the lib's response parser returned null on a send that had succeeded
        // on the wire — Telegram delivered both, and the user saw duplicate
        // menus. Plain sendMessage is deterministic.
        try {
            bot.sendMessage(
                chatId = ChatId.fromId(chatId),
                text = text,
                replyMarkup = keyboard,
                disableWebPagePreview = disableLinkPreview,
            ).getOrNull()?.messageId?.toString()
        } catch (e: Exception) {
            logger.warn("Telegram sendMenu failed: ${e.message}")
            null
        }
    }

    override suspend fun removeButtons(user: PlatformUser, messageId: String) {
        withContext(Dispatchers.IO) {
            val chatId = user.id.toLongOrNull()
            val msgId = messageId.toLongOrNull()
            if (chatId == null || msgId == null) return@withContext
            try {
                bot.editMessageReplyMarkup(
                    chatId = ChatId.fromId(chatId),
                    messageId = msgId,
                    replyMarkup = null,
                )
            } catch (e: Exception) {
                logger.warn("Failed to remove buttons: ${e.message}")
            }
        }
    }

    override suspend fun updateButtons(
        user: PlatformUser,
        messageId: String,
        options: List<MenuOption>,
        layout: MenuLayout,
    ) {
        withContext(Dispatchers.IO) {
            val chatId = user.id.toLongOrNull()
            val msgId = messageId.toLongOrNull()
            if (chatId == null || msgId == null) return@withContext

            val keyboard = keyboardForLayout(options, layout)
            try {
                bot.editMessageReplyMarkup(
                    chatId = ChatId.fromId(chatId),
                    messageId = msgId,
                    replyMarkup = keyboard,
                )
            } catch (e: Exception) {
                logger.warn("Failed to update buttons: ${e.message}")
            }
        }
    }

    override suspend fun sendTypingIndicator(user: PlatformUser) {
        withContext(Dispatchers.IO) {
            val chatId = user.id.toLongOrNull()
            if (chatId == null) {
                logger.error("Invalid Telegram user ID: ${user.id}")
                return@withContext
            }
            try {
                bot.sendChatAction(ChatId.fromId(chatId), ChatAction.TYPING)
            } catch (e: Exception) {
                logger.warn("Failed to send typing indicator: ${e.message}")
            }
        }
    }

    private fun keyboardForLayout(options: List<MenuOption>, layout: MenuLayout): InlineKeyboardMarkup =
        when (layout) {
            MenuLayout.INLINE -> inlineKeyboard(options)
            MenuLayout.VERTICAL -> InlineKeyboardMarkup.create(
                options.map { listOf(it.toButton()) },
            )
        }

    private fun inlineKeyboard(options: List<MenuOption>): InlineKeyboardMarkup =
        InlineKeyboardMarkup.create(
            options.chunked(INLINE_ROW_SIZE).map { row -> row.map { it.toButton() } },
        )

    private fun MenuOption.toButton(): InlineKeyboardButton =
        InlineKeyboardButton.CallbackData(label, id)
}
