package com.symptree.messaging.adapter

import com.symptree.messaging.port.MenuLayout
import com.symptree.messaging.port.MenuOption
import com.symptree.messaging.port.Platform
import com.symptree.messaging.port.PlatformUser
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC-W3WDWH.1 — every `MessagingPort` method on `TelegramAdapter` is wired against
 * the kotlin-telegram-bot library and produces the expected Bot API call.
 *
 * The `Bot` is constructed externally (mirroring the MMP-sibling pattern of
 * dependency-injecting the library handle) and pointed at a MockWebServer so
 * tests can assert on the Bot API request shape without bypassing the library.
 *
 * The library URL-encodes form bodies, so `reply_markup` arrives as a
 * percent-encoded JSON payload — assertions URL-decode the field and parse it
 * as JSON so we test the *semantic* request shape rather than its raw bytes.
 */
class TelegramAdapterTest {

    private lateinit var server: MockWebServer
    private val tgUser = PlatformUser(Platform.TELEGRAM, "12345", displayName = "Test User")
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun newAdapter(): TelegramAdapter {
        val testBot: Bot = bot {
            token = "test:token"
            apiUrl = server.url("/").toString()
        }
        return TelegramAdapter(testBot)
    }

    /** Parse a form-encoded request body into a `Map<String, String>`. */
    private fun parseFormBody(body: String): Map<String, String> =
        body.split('&')
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8)
                val value = URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
                key to value
            }
            .toMap()

    @Test
    fun `sendText posts to the Bot API sendMessage endpoint with chat_id and text`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"message_id":42}}"""))

        newAdapter().sendText(tgUser, "hello")

        // Same single-send regression as sendMenu: the markdown-fallback path
        // previously could fire twice on certain lib responses; pin request
        // count = 1 to catch re-introduction.
        assertEquals(1, server.requestCount, "sendText must make exactly one wire send")

        val request = server.takeRequest()
        assertTrue(
            request.path?.endsWith("/sendMessage") == true,
            "expected /sendMessage; got ${request.path}",
        )
        val form = parseFormBody(request.body.readUtf8())
        assertEquals("12345", form["chat_id"])
        assertEquals("hello", form["text"])
    }

    @Test
    fun `sendMenu posts a message with an inline_keyboard payload`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"message_id":99}}"""))

        val messageId = newAdapter().sendMenu(
            tgUser,
            "pick one",
            listOf(MenuOption("a", "Apple"), MenuOption("b", "Banana")),
            MenuLayout.INLINE,
        )

        // Regression: the prior markdown-fallback path called sendMessage
        // twice whenever the lib's response parser returned null on a send
        // that had succeeded on the wire — Telegram delivered both messages
        // and the user saw duplicate menus. Pinning request count = 1
        // catches any re-introduction of that double-send.
        assertEquals(1, server.requestCount, "sendMenu must make exactly one wire send (duplicate-send regression)")

        val request = server.takeRequest()
        assertTrue(
            request.path?.endsWith("/sendMessage") == true,
            "expected /sendMessage; got ${request.path}",
        )
        val form = parseFormBody(request.body.readUtf8())
        assertEquals("12345", form["chat_id"])
        assertEquals("pick one", form["text"])

        val markup = assertNotNull(form["reply_markup"], "expected reply_markup in form body")
        val parsed = json.parseToJsonElement(markup).jsonObject
        val keyboard: JsonArray = parsed["inline_keyboard"]?.jsonArray
            ?: error("reply_markup must contain inline_keyboard")
        assertEquals(1, keyboard.size, "expected one row for two options under INLINE layout")
        val firstRow = keyboard[0].jsonArray
        assertEquals(2, firstRow.size)
        val firstButton: JsonObject = firstRow[0].jsonObject
        assertEquals("Apple", firstButton["text"]?.jsonPrimitive?.content)
        assertEquals("a", firstButton["callback_data"]?.jsonPrimitive?.content)
        val secondButton: JsonObject = firstRow[1].jsonObject
        assertEquals("Banana", secondButton["text"]?.jsonPrimitive?.content)
        assertEquals("b", secondButton["callback_data"]?.jsonPrimitive?.content)

        assertEquals("99", messageId, "sendMenu must return the Telegram message_id as a string")
    }

    @Test
    fun `removeButtons calls editMessageReplyMarkup with empty markup`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":true}"""))

        newAdapter().removeButtons(tgUser, messageId = "555")

        val request = server.takeRequest()
        assertTrue(
            request.path?.endsWith("/editMessageReplyMarkup") == true,
            "expected /editMessageReplyMarkup; got ${request.path}",
        )
        val form = parseFormBody(request.body.readUtf8())
        assertEquals("555", form["message_id"])
    }

    @Test
    fun `updateButtons calls editMessageReplyMarkup with a new inline_keyboard`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":true}"""))

        newAdapter().updateButtons(
            tgUser,
            messageId = "777",
            options = listOf(MenuOption("x", "Xenon")),
            layout = MenuLayout.INLINE,
        )

        val request = server.takeRequest()
        assertTrue(
            request.path?.endsWith("/editMessageReplyMarkup") == true,
            "expected /editMessageReplyMarkup; got ${request.path}",
        )
        val form = parseFormBody(request.body.readUtf8())
        assertEquals("777", form["message_id"])
        val markup = assertNotNull(form["reply_markup"], "expected reply_markup in body")
        val parsed = json.parseToJsonElement(markup).jsonObject
        val keyboard = parsed["inline_keyboard"]?.jsonArray
            ?: error("expected inline_keyboard in reply_markup")
        assertEquals(1, keyboard.size)
        val button = keyboard[0].jsonArray[0].jsonObject
        assertEquals("Xenon", button["text"]?.jsonPrimitive?.content)
        assertEquals("x", button["callback_data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sendTypingIndicator calls sendChatAction with action=typing`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":true}"""))

        newAdapter().sendTypingIndicator(tgUser)

        val request = server.takeRequest()
        assertTrue(
            request.path?.endsWith("/sendChatAction") == true,
            "expected /sendChatAction; got ${request.path}",
        )
        val form = parseFormBody(request.body.readUtf8())
        assertEquals("12345", form["chat_id"])
        assertEquals("typing", form["action"])
    }

    @Test
    fun `sendPhoto posts to the Bot API sendPhoto endpoint with multipart bytes`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"message_id":33}}"""))

        val photoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)  // PNG header
        val returned = newAdapter().sendPhoto(
            tgUser,
            photo = photoBytes,
            mimeType = "image/png",
            caption = "Look here",
            options = emptyList(),
        )

        val request = server.takeRequest()
        assertTrue(
            request.path?.endsWith("/sendPhoto") == true,
            "expected /sendPhoto; got ${request.path}",
        )
        assertNotNull(returned, "sendPhoto must return the resulting message_id (not null)")
        assertEquals("33", returned)
    }
}
