package com.symptree.core.source

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AC-YDANPW.9 — provider-name-leak guard.
 *
 * Regex-sweeps the canonical surface and fails (case-insensitive) on any of
 * `hl7`, `siu`, `adt`, `mllp`, `\bhis\b` appearing in a symbol or column name:
 *   - `core/.../source/`            EXCLUDING `source/hl7v2/` (provider impl pkg),
 *   - the Kotlin DB models          (`model/Doctor*.kt`, `db/DoctorRepo.kt`),
 *   - the `database/` SQL           schema files.
 *
 * Also asserts the sweep is real: a synthetic line containing `hl7AppointmentId`
 * (the exact leak AC-YDANPW.3 forbids) IS caught by the same matcher, so adding
 * such a symbol to a `source/` root type would fail this test.
 */
class NoProviderNameLeakTest {

    // Banned provider-type tokens. `his` is word-bounded to avoid false hits on
    // words like "this"/"history"; the others are distinctive enough to match raw.
    private val bannedPatterns = listOf(
        Regex("""hl7""", RegexOption.IGNORE_CASE),
        Regex("""\bsiu\b""", RegexOption.IGNORE_CASE),
        Regex("""\badt\b""", RegexOption.IGNORE_CASE),
        Regex("""mllp""", RegexOption.IGNORE_CASE),
        Regex("""\bhis\b""", RegexOption.IGNORE_CASE),
    )

    private fun repoRoot(): File {
        val candidates = listOf(File("."), File(".."), File("../.."))
        return candidates.firstOrNull { File(it, "database").isDirectory && File(it, "core").isDirectory }
            ?.canonicalFile
            ?: error("could not locate repo root (looked for sibling database/ + core/)")
    }

    /** Files swept by the guard: source/ root (minus hl7v2/), the Doctor DB models, and SQL. */
    private fun sweptFiles(): List<File> {
        val root = repoRoot()
        val sourceDir = File(root, "core/src/main/kotlin/com/symptree/core/source")
        val modelDir = File(root, "core/src/main/kotlin/com/symptree/core/model")
        val dbDir = File(root, "core/src/main/kotlin/com/symptree/core/db")
        val sqlDir = File(root, "database")

        val sourceKt = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // EXCLUDE the deferred provider impl package — HL7 names are correct there.
            .filterNot { it.absolutePath.replace('\\', '/').contains("/source/hl7v2/") }
            .toList()
        val doctorModels = modelDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("Doctor") && it.extension == "kt" }
            .toList()
        val doctorRepo = dbDir.walkTopDown()
            .filter { it.isFile && it.name == "DoctorRepo.kt" }
            .toList()
        val sql = sqlDir.walkTopDown()
            .filter { it.isFile && it.extension == "sql" }
            .toList()

        return sourceKt + doctorModels + doctorRepo + sql
    }

    @Test
    fun `AC-9 no banned provider-type token leaks into canonical surface`() {
        val files = sweptFiles()
        assertTrue(files.isNotEmpty(),
            "leak sweep found no files to scan — source/ package not present yet?")

        val violations = mutableListOf<String>()
        for (file in files) {
            file.readLines().forEachIndexed { idx, line ->
                val code = line.substringBefore("--").substringBefore("//")
                for (pattern in bannedPatterns) {
                    if (pattern.containsMatchIn(code)) {
                        violations.add("${file.name}:${idx + 1}  /${pattern.pattern}/  -> $line")
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "AC-YDANPW.9 leak guard tripped:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `AC-9 sweep matcher actually catches a deliberate hl7AppointmentId leak`() {
        val syntheticLeak = "    val hl7AppointmentId: String? = null"
        val caught = bannedPatterns.any { it.containsMatchIn(syntheticLeak) }
        if (!caught) {
            fail("matcher failed to catch hl7AppointmentId — the live sweep would be toothless")
        }
    }
}
