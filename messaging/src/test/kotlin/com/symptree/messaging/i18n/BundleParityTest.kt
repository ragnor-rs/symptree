package com.symptree.messaging.i18n

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-VWRNDR / AC-VWRNDR.8a — ru↔kk **bundle parity** over the **full** unified
 * catalog. Both language bundles must carry the exact same key set (no key may
 * exist in one language and not the other), and every value must be non-blank.
 */
class BundleParityTest {

    private val catalog = MessageCatalog.loadFromClasspath()

    @Test
    fun `ru and kk bundles carry the identical key set`() {
        val ru = catalog.keys("ru")
        val kk = catalog.keys("kk")
        assertTrue(ru.isNotEmpty() && kk.isNotEmpty(), "both bundles must load")
        assertEquals(emptySet(), ru - kk, "keys present in ru but missing in kk")
        assertEquals(emptySet(), kk - ru, "keys present in kk but missing in ru")
    }

    @Test
    fun `every key resolves to a non-blank value in both languages`() {
        for (lang in listOf("ru", "kk")) {
            for (key in catalog.keys(lang)) {
                val value = catalog.get(key, lang)
                assertTrue(value.isNotBlank(), "blank value for '$key' [$lang]")
            }
        }
    }
}
