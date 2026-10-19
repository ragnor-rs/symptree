package com.symptree.core.i18n

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * AC-W3WDWH.9 — build-time sweep: no user-facing string literal in Russian
 * Cyrillic or Kazakh Cyrillic may live in non-test code under the directories
 * listed by [SWEEPED_DIRS]. All such chrome strings must come from a catalog.
 *
 * FR-VWRNDR supersedes the old "core ships the i18n catalog" half of AC-W3WDWH.9:
 * the catalog (keys + bundles) now lives **only** in `messaging` (see that module's
 * `MessageCatalogTest` / `BundleParityTest`), and `core` ships zero presentation
 * strings (guarded by [CoreHasNoChromeTest]). This sweep remains the complementary
 * guard that no Cyrillic chrome literal is hardcoded in production Kotlin.
 *
 * The sweep skips:
 *  - test sources (`src/test/`)
 *  - the i18n/ resource directories (the catalog itself)
 *  - the algorithms/ resource directory (algorithm trees live there)
 *  - file-header / KDoc comment blocks (` * `-prefixed lines and `//`-prefixed
 *    full-line comments) where Russian / Kazakh helper docs are legitimate.
 *
 * A *literal* is detected as a quoted Kotlin string `"..."` containing a
 * Cyrillic letter. Multi-line literals (`""" ... """`) are also scanned.
 */
class NoHardcodedLiteralsTest {

    private val cyrillicLiteralRegex = Regex("\"[^\"]*[\\u0400-\\u04FF][^\"]*\"")
    // Triple-quoted heredocs frequently embed JSON for testing; only scan single-line `"..."`
    // forms for non-test files. The directories swept here are production code only, so this
    // narrow regex is enough.

    // FR-W3WDWH.9 spec lists: routes/, core/, adapter/, client/, booking/, scheduler/.
    // The matcher does a path-substring check, so `"core"` matches BOTH
    // `messaging/.../messaging/core/` (handler graph) AND
    // `core/.../com/symptree/core/` (core module). That's the intended sweep
    // surface — the user-facing Telegram-bot wiring on both sides.
    private val sweepedDirs = listOf(
        "routes",
        "core",        // messaging/.../core/ (handlers) + core/.../com/symptree/core/
        "i18n",        // catalog impl itself — code, not yaml
        "adapter",     // messaging adapter implementations
        "client",      // messaging HTTP clients
        "booking",     // FR-4GCYJK lands here (M3)
        "scheduler",   // FR-4GCYJK reminder scheduler (M3)
    )

    @Test
    fun `core ships no i18n catalog bundles - they relocated to messaging (FR-VWRNDR)`() {
        // AC-VWRNDR.1 reverses the old "core ships the catalog" contract: the i18n
        // bundles now live only in messaging. Assert core ships none.
        val coreI18n = listOf(
            File("src/main/resources/i18n"),
            File("core/src/main/resources/i18n"),
        )
        for (dir in coreI18n) {
            assertTrue(!dir.exists(), "core must not ship an i18n bundle dir; found ${dir.absolutePath}")
        }
    }

    @Test
    fun `no production Kotlin file under the swept directories contains a Cyrillic string literal`() {
        // The test runs from the `core/` module dir under Gradle. Walk both core and messaging
        // source trees so the sweep is module-spanning.
        val moduleRoots = listOf(
            File("src/main/kotlin"),
            File("../messaging/src/main/kotlin"),
        ).filter { it.isDirectory }
        assertTrue(moduleRoots.isNotEmpty(), "expected to find at least one src/main/kotlin tree")

        val violations = mutableListOf<String>()
        for (root in moduleRoots) {
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .filter { f -> sweepedDirs.any { dir -> f.path.contains("/$dir/") } }
                .filter { !it.path.contains("/src/test/") }
                .forEach { file ->
                    val text = file.readText()
                    // Skip comment-only lines (// and * leading) before checking for Cyrillic strings.
                    val codeLines = text.lineSequence()
                        .filter { line ->
                            val trimmed = line.trim()
                            !trimmed.startsWith("//") &&
                                !trimmed.startsWith("*") &&
                                !trimmed.startsWith("/*")
                        }
                        .joinToString("\n")
                    val matches = cyrillicLiteralRegex.findAll(codeLines).map { it.value }.toList()
                    if (matches.isNotEmpty()) {
                        violations.add("${file.path}: ${matches.take(3)}")
                    }
                }
        }
        assertTrue(
            violations.isEmpty(),
            "AC-W3WDWH.9: every user-facing string must come from MessageCatalog.get;" +
                " offending Cyrillic literals found:\n${violations.joinToString("\n")}",
        )
    }
}
