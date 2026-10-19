package com.symptree.messaging

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * When a bot message's button is pressed, the chosen option is appended as a
 * confirmation line. A leading ✅ marks the pick — UNLESS the label already
 * starts with its own emoji (the menu labels all do), so we never stack two
 * emoji at the line start. See [selectedConfirmation] / [startsWithEmoji].
 */
class SelectedConfirmationTest {

    @Test
    fun `plain-text label gets the checkmark prefix`() {
        assertEquals("✅ Да", selectedConfirmation("Да"))
        assertEquals("✅ Russian", selectedConfirmation("Russian"))
    }

    @Test
    fun `label already starting with an emoji is left unprefixed`() {
        // Real bundle labels across the emoji blocks the bot uses.
        assertEquals("🩺 Разобраться с симптомами", selectedConfirmation("🩺 Разобраться с симптомами"))
        assertEquals("📅 Записаться к врачу", selectedConfirmation("📅 Записаться к врачу"))
        assertEquals("✅ Да, записаться", selectedConfirmation("✅ Да, записаться"))
        assertEquals("🔄 Другое время", selectedConfirmation("🔄 Другое время"))
        assertEquals("ℹ️ Узнать об услугах", selectedConfirmation("ℹ️ Узнать об услугах"))
    }

    @Test
    fun `startsWithEmoji distinguishes emoji from text and tolerates leading space`() {
        assertEquals(true, startsWithEmoji("🚨 emergency"))
        assertEquals(true, startsWithEmoji("  📌 leading space"))
        assertEquals(false, startsWithEmoji("Текст"))
        assertEquals(false, startsWithEmoji("Plain"))
        assertEquals(false, startsWithEmoji(""))
    }
}
