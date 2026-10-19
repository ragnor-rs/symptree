package com.symptree.core.i18n

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-VWRNDR / AC-VWRNDR.1 + .8b — the **chrome-literal sweep** guard.
 *
 * `core` must hold **zero** presentation strings and **zero** localization keys on
 * either path. This statically sweeps the whole `core` main source tree (and its
 * resources) and asserts:
 *  - no `MessageCatalog` class / `catalog.get(...)` call site survives,
 *  - no `i18n` yaml bundle resource survives,
 *  - no `"bot.*"` localization-key literal appears,
 *  - no urgency-icon chrome glyph appears.
 *
 * Domain-data Cyrillic (e.g. an LLM prompt) is fine — only catalog keys, the
 * catalog API, and chrome glyphs are forbidden.
 */
class CoreHasNoChromeTest {

    private val mainKotlinRoot: File = listOf(
        File("src/main/kotlin"),
        File("core/src/main/kotlin"),
    ).firstOrNull { it.isDirectory } ?: error("could not locate core main source root")

    private val mainResourcesRoot: File? = listOf(
        File("src/main/resources"),
        File("core/src/main/resources"),
    ).firstOrNull { it.isDirectory }

    private fun kotlinSources(): List<File> =
        mainKotlinRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `core has no catalog API usage`() {
        val offenders = kotlinSources().filter { f ->
            val t = f.readText()
            t.contains("catalog.get(") || t.contains("MessageCatalog")
        }
        assertTrue(offenders.isEmpty(), "core must not reference a presentation catalog; offenders: ${offenders.map { it.path }}")
    }

    @Test
    fun `core ships no i18n bundle resources`() {
        val i18nDir = mainResourcesRoot?.resolve("i18n")
        assertFalse(i18nDir != null && i18nDir.exists(), "core must not ship i18n/*.yaml bundles; found ${i18nDir?.path}")
    }

    @Test
    fun `core contains no localization-key literals`() {
        val offenders = kotlinSources().filter { it.readText().contains("\"bot.") }
        assertTrue(
            offenders.isEmpty(),
            "core must hold no \"bot.*\" localization-key constants; offenders: ${offenders.map { it.path }}",
        )
    }

    @Test
    fun `core contains no urgency-icon chrome glyphs`() {
        val icons = listOf("🚨", "⚠️", "📌", "🏠")
        val offenders = kotlinSources().filter { f -> icons.any { f.readText().contains(it) } }
        assertTrue(
            offenders.isEmpty(),
            "urgency icons are view artifacts owned by the frontend; offenders: ${offenders.map { it.path }}",
        )
    }
}
