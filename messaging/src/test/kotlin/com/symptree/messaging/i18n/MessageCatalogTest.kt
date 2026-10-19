package com.symptree.messaging.i18n

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-VWRNDR / AC-VWRNDR.6 — the **single** unified view-layer catalog (relocated +
 * unified from the former core `MessageCatalog` and messaging `OutgoingMessageCatalog`).
 * Covers key lookup, `{param}` interpolation, and missing-key fallback over the
 * full catalog, plus a no-unresolved-token check for the dispatched reminder keys.
 */
class MessageCatalogTest {

    private val catalog = MessageCatalog.loadFromClasspath()

    @Test
    fun `loads both ru and kk bundles`() {
        assertEquals(setOf("ru", "kk"), catalog.languages(), "the unified catalog must back both languages")
    }

    @Test
    fun `get returns the translated string for a known key in the requested language`() {
        val ru = catalog.get("bot.welcome.menu_prompt", "ru")
        val kk = catalog.get("bot.welcome.menu_prompt", "kk")
        assertTrue(ru.isNotBlank() && ru != "bot.welcome.menu_prompt", "ru lookup must resolve")
        assertTrue(kk.isNotBlank() && kk != "bot.welcome.menu_prompt", "kk lookup must resolve")
        assertTrue(ru != kk, "ru and kk renderings must differ")
    }

    @Test
    fun `get with an unknown key returns the key itself as a visible marker`() {
        assertEquals("bot.does.not.exist", catalog.get("bot.does.not.exist", "ru"))
    }

    @Test
    fun `get interpolates named params`() {
        val rendered = catalog.get("bot.booking.confirm_slot", "ru", mapOf("doctor" to "Иванова", "datetime" to "10.07 14:00"))
        assertTrue(rendered.contains("Иванова"), "{doctor} must interpolate; got '$rendered'")
        assertTrue(rendered.contains("10.07 14:00"), "{datetime} must interpolate; got '$rendered'")
        assertFalse(rendered.contains("{"), "no unresolved token may remain; got '$rendered'")
    }

    @Test
    fun `every dispatched reminder key renders with no unresolved token in every language`() {
        val params = mapOf("doctor" to "Иванова", "time" to "14:00")
        val dispatchedKeys = listOf(
            "bot.reminder.pre_visit_24h",
            "bot.reminder.same_day",
            "bot.feedback.prompt",
        )
        for (lang in listOf("ru", "kk")) {
            for (key in dispatchedKeys) {
                val rendered = catalog.get(key, lang, params)
                assertFalse(rendered.contains("{"), "rendered '$key' [$lang] leaked a token: '$rendered'")
                assertTrue(rendered != key, "missing template for '$key' [$lang]")
            }
        }
    }
}
