package com.symptree.messaging.core

import com.symptree.messaging.client.CoreClient
import com.symptree.messaging.i18n.MessageCatalog
import com.symptree.messaging.port.GoalPromptKind
import com.symptree.messaging.port.IncomingMessage
import com.symptree.messaging.port.IngressOutcome
import com.symptree.messaging.port.IngressResponse
import com.symptree.messaging.port.MenuLayout
import com.symptree.messaging.port.MenuOption
import com.symptree.messaging.port.MessagingPort
import com.symptree.messaging.port.OptionDto
import com.symptree.messaging.port.Platform
import com.symptree.messaging.port.PlatformUser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * FR-VWRNDR — the bot dispatch loop posts to `/bot/ingress`, receives a **semantic**
 * `IngressResponse`, renders it via [IngressRenderer] using the resolved session
 * language, and replays the resulting [com.symptree.messaging.port.OutgoingAction]s
 * through the [MessagingPort] in order.
 */
class IngressHandlerTest {

    private val tgUser = PlatformUser(Platform.TELEGRAM, "tg-77")

    private val catalog = MessageCatalog(
        bundles = mapOf(
            "ru" to mapOf("bot.symptom.prompt_main_complaint" to "Расскажите, что беспокоит."),
        ),
    )
    private val renderer = IngressRenderer(catalog)

    /** In-memory CoreClient stub recording the ingress arguments. */
    private class StubCoreClient(
        private val canned: IngressResponse,
    ) : CoreClient(baseUrl = "http://stub") {
        var lastSlug: String? = null
        var lastIncoming: IncomingMessage? = null
        var callCount: Int = 0

        override suspend fun botIngress(clinicSlug: String, incoming: IncomingMessage): IngressResponse {
            lastSlug = clinicSlug
            lastIncoming = incoming
            callCount += 1
            return canned
        }
    }

    /** Recording MessagingPort — every call appended to [calls]. */
    private class RecordingPort : MessagingPort {
        val calls: MutableList<String> = mutableListOf()
        override suspend fun sendText(user: PlatformUser, text: String, disableLinkPreview: Boolean) {
            calls += "sendText:$text"
        }
        override suspend fun sendPhoto(
            user: PlatformUser,
            photo: ByteArray,
            mimeType: String,
            caption: String?,
            options: List<MenuOption>,
        ): String? {
            calls += "sendPhoto:${photo.size}"
            return "mid"
        }
        override suspend fun sendMenu(
            user: PlatformUser,
            text: String,
            options: List<MenuOption>,
            layout: MenuLayout,
            disableLinkPreview: Boolean,
        ): String? {
            calls += "sendMenu:$text:${options.size}"
            return "mid"
        }
        override suspend fun removeButtons(user: PlatformUser, messageId: String) {
            calls += "removeButtons:$messageId"
        }
        override suspend fun updateButtons(
            user: PlatformUser,
            messageId: String,
            options: List<MenuOption>,
            layout: MenuLayout,
        ) {
            calls += "updateButtons:$messageId"
        }
        override suspend fun sendTypingIndicator(user: PlatformUser) {
            calls += "sendTypingIndicator"
        }
    }

    @Test
    fun `handle posts the normalized incoming message to bot ingress with the configured clinic slug`() = runBlocking {
        val stub = StubCoreClient(IngressResponse("ru", emptyList()))
        val port = RecordingPort()
        val handler = IngressHandler(stub, port, clinicSlug = "clinic-acme", renderer = renderer)

        val incoming = IncomingMessage(tgUser, text = "hello")
        handler.handle(incoming)

        assertEquals("clinic-acme", stub.lastSlug, "ingress must carry the configured clinic slug")
        assertEquals(incoming, stub.lastIncoming)
        assertEquals(1, stub.callCount, "bot ingress must be called exactly once per update")
    }

    @Test
    fun `handle renders each semantic outcome and replays the actions in order`() = runBlocking {
        val stub = StubCoreClient(
            IngressResponse(
                lang = "ru",
                outcomes = listOf(
                    IngressOutcome.GoalPrompt(GoalPromptKind.MAIN_COMPLAINT),
                    IngressOutcome.NextQuestion(
                        nodeId = "q1",
                        text = "Болит сейчас?",
                        options = listOf(OptionDto("yes", "Да"), OptionDto("no", "Нет")),
                    ),
                ),
            ),
        )
        val port = RecordingPort()
        val handler = IngressHandler(stub, port, clinicSlug = "c1", renderer = renderer)

        handler.handle(IncomingMessage(tgUser, text = "hi"))

        assertEquals(
            listOf(
                "sendText:Расскажите, что беспокоит.",
                "sendMenu:Болит сейчас?:2",
            ),
            port.calls,
            "each semantic outcome must be rendered + replayed in order via the MessagingPort",
        )
    }
}
