package com.symptree.messaging.outgoing

import com.symptree.messaging.i18n.MessageCatalog
import com.symptree.messaging.port.MenuLayout
import com.symptree.messaging.port.MenuOption
import com.symptree.messaging.port.MessagingPort
import com.symptree.messaging.port.PlatformUser
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-VWRNDR — `POST /bot-internal/outgoing` on the messaging module.
 *
 *  - shared-secret auth via `BOT_INTERNAL_SHARED_SECRET` (distinct from ingress secret),
 *  - body is the **semantic** `{platform_user_id, language, kind, doctor_name, slot_at, clinic_zone}`,
 *  - maps the typed `kind` to the frontend's catalog key, formats `slot_at` in
 *    `clinic_zone`, renders via the unified [MessageCatalog] + `MessagingPort.sendText`,
 *  - 401 wrong secret / 422 missing-or-unknown `kind` (incl. a legacy `catalog_key` body) / 200 on send.
 */
class BotInternalOutgoingRoutesTest {

    private val secret = "outgoing-secret"

    /** Records sendText calls so the render + dispatch side effect can be asserted. */
    private class RecordingPort : MessagingPort {
        data class Sent(val userId: String, val text: String)
        val sent = mutableListOf<Sent>()
        override suspend fun sendText(user: PlatformUser, text: String, disableLinkPreview: Boolean) {
            sent += Sent(user.id, text)
        }
        override suspend fun sendPhoto(user: PlatformUser, photo: ByteArray, mimeType: String, caption: String?, options: List<MenuOption>): String? = null
        override suspend fun sendMenu(user: PlatformUser, text: String, options: List<MenuOption>, layout: MenuLayout, disableLinkPreview: Boolean): String? = null
        override suspend fun removeButtons(user: PlatformUser, messageId: String) = Unit
        override suspend fun updateButtons(user: PlatformUser, messageId: String, options: List<MenuOption>, layout: MenuLayout) = Unit
        override suspend fun sendTypingIndicator(user: PlatformUser) = Unit
    }

    private val catalog = MessageCatalog(
        bundles = mapOf(
            "ru" to mapOf("bot.reminder.pre_visit_24h" to "Напоминание: визит к {doctor} завтра в {time}."),
        ),
    )

    // 2026-07-10T09:00:00Z → 14:00 local in Asia/Almaty (UTC+5).
    private val slotAtMillis = 1_783_674_000_000L

    private fun io.ktor.server.testing.ApplicationTestBuilder.install(port: MessagingPort) {
        application {
            routing {
                botInternalOutgoingRoutes(catalog = catalog, port = port, sharedSecret = secret)
            }
        }
    }

    @Test
    fun `200 maps the semantic kind to a catalog key, formats the slot, and sends`() = testApplication {
        val port = RecordingPort()
        install(port)

        val resp = client.post("/bot-internal/outgoing") {
            contentType(ContentType.Application.Json)
            header("X-Internal-Secret", secret)
            setBody(
                """{"platform_user_id":"tg-1","language":"ru","kind":"pre_visit_24h",""" +
                    """"doctor_name":"Иванова","slot_at":$slotAtMillis,"clinic_zone":"Asia/Almaty"}""",
            )
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(1, port.sent.size, "exactly one outbound text must be sent")
        assertEquals("tg-1", port.sent.single().userId)
        val text = port.sent.single().text
        assertTrue(text.contains("Иванова"), "the {doctor} param must be interpolated; got '$text'")
        assertTrue(text.contains("14:00"), "the frontend must format {time} in the clinic zone; got '$text'")
    }

    @Test
    fun `401 on a wrong shared secret and nothing is sent`() = testApplication {
        val port = RecordingPort()
        install(port)

        val resp = client.post("/bot-internal/outgoing") {
            contentType(ContentType.Application.Json)
            header("X-Internal-Secret", "WRONG")
            setBody("""{"platform_user_id":"tg-1","language":"ru","kind":"pre_visit_24h","doctor_name":"X","slot_at":$slotAtMillis,"clinic_zone":"UTC"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(port.sent.isEmpty(), "no message may be sent on an auth failure")
    }

    @Test
    fun `422 rejects a legacy catalog_key body that carries no semantic kind`() = testApplication {
        // AC-VWRNDR.5 — the wire no longer accepts catalog_key. A body that carries
        // it (and no `kind`) must be rejected, nothing sent.
        val port = RecordingPort()
        install(port)

        val resp = client.post("/bot-internal/outgoing") {
            contentType(ContentType.Application.Json)
            header("X-Internal-Secret", secret)
            setBody("""{"platform_user_id":"tg-1","language":"ru","catalog_key":"bot.reminder.pre_visit_24h","params":{}}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        assertTrue(port.sent.isEmpty(), "a body carrying catalog_key (no kind) must not dispatch")
    }

    @Test
    fun `422 rejects an unknown reminder kind`() = testApplication {
        val port = RecordingPort()
        install(port)

        val resp = client.post("/bot-internal/outgoing") {
            contentType(ContentType.Application.Json)
            header("X-Internal-Secret", secret)
            setBody("""{"platform_user_id":"tg-1","language":"ru","kind":"not_a_kind","doctor_name":"X","slot_at":$slotAtMillis,"clinic_zone":"UTC"}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        assertTrue(port.sent.isEmpty(), "an unknown kind must not map to a key and must not dispatch")
    }
}
