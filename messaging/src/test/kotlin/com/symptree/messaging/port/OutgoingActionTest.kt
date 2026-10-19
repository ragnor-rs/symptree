package com.symptree.messaging.port

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire-contract regression for [OutgoingAction]. The backend (core module)
 * serializes [com.symptree.core.routes.OutgoingActionDto] with short-name
 * discriminators (`"type":"SendText"`, etc.); the bot (this module) MUST
 * deserialize the same shape. Catches the production bug where the missing
 * `@SerialName` on subclasses produced FQ-class-name discriminators (e.g.
 * `"type":"com.symptree.messaging.port.OutgoingAction.SendText"`) that the
 * backend never emits, and `kotlinx.serialization` threw
 * `Serializer for subclass 'SendText' is not found in the polymorphic scope`
 * at the bot's first response.
 */
class OutgoingActionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `SendText round-trips with the literal short-name discriminator backend emits`() {
        val original: OutgoingAction = OutgoingAction.SendText(text = "hello")
        val serialized = json.encodeToString(OutgoingAction.serializer(), original)
        val parsed = json.parseToJsonElement(serialized).jsonObject
        assertEquals(
            "SendText",
            parsed["type"]?.jsonPrimitive?.content,
            "discriminator must be the short class name; FQ-class-name signals a missing @SerialName",
        )
        assertEquals("hello", parsed["text"]?.jsonPrimitive?.content)

        val decoded = json.decodeFromString(OutgoingAction.serializer(), serialized)
        assertEquals(original, decoded)
    }

    @Test
    fun `SendMenu round-trips with options and layout`() {
        val original: OutgoingAction = OutgoingAction.SendMenu(
            text = "pick",
            options = listOf(MenuOption(id = "a", label = "Apple"), MenuOption(id = "b", label = "Banana")),
            layout = MenuLayout.VERTICAL,
        )
        val serialized = json.encodeToString(OutgoingAction.serializer(), original)
        val parsed = json.parseToJsonElement(serialized).jsonObject
        assertEquals("SendMenu", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("pick", parsed["text"]?.jsonPrimitive?.content)
        val opts = parsed["options"]?.jsonArray ?: error("options array required")
        assertEquals(2, opts.size)
        assertEquals("a", opts[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("Apple", opts[0].jsonObject["label"]?.jsonPrimitive?.content)
        assertEquals("VERTICAL", parsed["layout"]?.jsonPrimitive?.content)

        val decoded = json.decodeFromString(OutgoingAction.serializer(), serialized)
        assertEquals(original, decoded)
    }

    @Test
    fun `every OutgoingAction subclass round-trips via the sealed-type serializer`() {
        val originals: List<OutgoingAction> = listOf(
            OutgoingAction.SendText(text = "x"),
            OutgoingAction.SendMenu(
                text = "y",
                options = listOf(MenuOption("a", "A")),
                layout = MenuLayout.INLINE,
            ),
            OutgoingAction.SendTyping(),
            OutgoingAction.RemoveButtons(messageId = "123"),
            OutgoingAction.UpdateButtons(
                messageId = "456",
                options = listOf(MenuOption("ok", "OK")),
                layout = MenuLayout.VERTICAL,
            ),
        )
        val listSer = ListSerializer(OutgoingAction.serializer())
        val serialized = json.encodeToString(listSer, originals)

        // Every variant must carry the literal short-name discriminator the
        // backend emits. If any variant's `@SerialName` regresses to its FQ
        // class name, this test fails at the assertion below.
        val parsedList = json.parseToJsonElement(serialized).jsonArray
        val discriminators = parsedList.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        val expectedShortNames = listOf("SendText", "SendMenu", "SendTyping", "RemoveButtons", "UpdateButtons")
        assertEquals(expectedShortNames, discriminators)

        val decoded = json.decodeFromString(listSer, serialized)
        assertEquals(originals, decoded)
    }

    @Test
    fun `decoding the exact wire shape the backend emits succeeds`() {
        // Pinned wire payload — if the backend's OutgoingActionDto subclass
        // SerialName values ever drift from this list, the bot can't decode
        // and this test fails. Matches the on-the-wire shape of
        // `core/src/main/kotlin/com/symptree/core/routes/BotIngressDtos.kt`.
        val pinnedWire = """
            [
              {"type":"SendText","text":"Здравствуйте!","disableLinkPreview":false},
              {"type":"SendMenu","text":"Чем помочь?","options":[
                  {"id":"goal:symptom_consultation","label":"🩺 ..."}
                ],"layout":"VERTICAL","disableLinkPreview":false}
            ]
        """.trimIndent()
        val listSer = ListSerializer(OutgoingAction.serializer())
        val decoded = json.decodeFromString(listSer, pinnedWire)
        assertEquals(2, decoded.size)
        assertTrue(decoded[0] is OutgoingAction.SendText)
        assertTrue(decoded[1] is OutgoingAction.SendMenu)
        val menu = decoded[1] as OutgoingAction.SendMenu
        assertEquals(1, menu.options.size)
        assertEquals("goal:symptom_consultation", menu.options[0].id)
    }
}
