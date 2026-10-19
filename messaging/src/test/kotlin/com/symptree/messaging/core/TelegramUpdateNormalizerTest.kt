package com.symptree.messaging.core

import com.symptree.messaging.port.Platform
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * AC-W3WDWH.3 — language detection from the Telegram update.
 *
 * The bot extracts `Update.message.from.language_code` and surfaces it on the
 * [com.symptree.messaging.port.PlatformUser] inside the normalized
 * [com.symptree.messaging.port.IncomingMessage]. The backend route picks the
 * effective language (allowed_languages → default_language fallback); the bot
 * just carries the hint forward.
 *
 * The normalizer is the bot-side input pipeline: it takes a raw
 * `com.github.kotlintelegrambot.entities.Update` and emits an `IncomingMessage`.
 */
class TelegramUpdateNormalizerTest {

    @Test
    fun `normalizer copies Telegram language_code into PlatformUser languageCode`() {
        val update = TelegramFixtures.textUpdate(
            chatId = 555L,
            text = "у меня болит",
            firstName = "Aigul",
            languageCode = "kk",
        )

        val incoming = TelegramUpdateNormalizer.normalize(update)

        assertNotNull(incoming, "normalize must produce an IncomingMessage for a text update")
        assertEquals(Platform.TELEGRAM, incoming.user.platform)
        assertEquals("555", incoming.user.id, "PlatformUser id must be the Telegram chat id as a string")
        assertEquals("kk", incoming.user.languageCode, "language_code must be propagated to PlatformUser")
        assertEquals("у меня болит", incoming.text)
    }

    @Test
    fun `normalizer returns a PlatformUser with null languageCode when Telegram omits the field`() {
        val update = TelegramFixtures.textUpdate(
            chatId = 12L,
            text = "hello",
            firstName = "Anon",
            languageCode = null,
        )

        val incoming = TelegramUpdateNormalizer.normalize(update)
        assertNotNull(incoming)
        assertEquals(null, incoming.user.languageCode, "missing language_code must map to null, not empty string")
    }

    @Test
    fun `normalizer maps callback_query data into IncomingMessage callbackData`() {
        val update = TelegramFixtures.callbackUpdate(
            chatId = 100L,
            callbackData = "option:yes",
            firstName = "X",
            languageCode = "ru",
        )

        val incoming = TelegramUpdateNormalizer.normalize(update)
        assertNotNull(incoming, "callback updates must normalize to an IncomingMessage")
        assertEquals("option:yes", incoming.callbackData)
        assertEquals("ru", incoming.user.languageCode)
    }
}
