package com.symptree.core.source

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * AC-YDANPW.10 — guard test over technical-spec.md § 3.4.
 *
 * §3.4 was repositioned by spec-write to present the `MedicalDataSource`
 * abstraction (not the old `ClinicAdapter` port). This is a *regression guard*:
 * it documents the invariant and is expected to PASS against current specs —
 * it would go RED if §3.4 ever regressed to presenting `ClinicAdapter` as a
 * live port or reintroduced `BookingResult.hl7AppointmentId`.
 */
class TechnicalSpecSection34Test {

    private fun specFile(): File {
        val candidates = listOf(
            File("specs/technical-spec.md"),
            File("../specs/technical-spec.md"),
            File("../../specs/technical-spec.md"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("could not locate specs/technical-spec.md; tried ${candidates.map { it.absolutePath }}")
    }

    /** Extract the body of the "### 3.4 ..." section up to the next "### " heading. */
    private fun section34(text: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("### 3.4") }
        require(start >= 0) { "§3.4 heading not found in technical-spec.md" }
        val rest = lines.drop(start + 1)
        val endRel = rest.indexOfFirst { it.startsWith("### ") }
        val body = if (endRel >= 0) rest.take(endRel) else rest
        return body.joinToString("\n")
    }

    @Test
    fun `AC-10 section 3-4 presents MedicalDataSource and BookingResult externalRef`() {
        val body = section34(specFile().readText())
        assertTrue("MedicalDataSource" in body,
            "§3.4 must present the MedicalDataSource abstraction")
        assertTrue(Regex("""BookingResult\b[^\n]*externalRef""").containsMatchIn(body),
            "§3.4 must define BookingResult with externalRef")
    }

    @Test
    fun `AC-10 section 3-4 does not present ClinicAdapter as a live port nor hl7AppointmentId`() {
        val body = section34(specFile().readText())
        // ClinicAdapter may only be referenced as the *replaced* port, never reintroduced
        // as a current interface/class definition.
        assertTrue(!Regex("""(interface|class)\s+ClinicAdapter""").containsMatchIn(body),
            "§3.4 must not define ClinicAdapter as a live port")
        assertTrue("hl7AppointmentId" !in body,
            "§3.4 must not reintroduce BookingResult.hl7AppointmentId")
    }
}
