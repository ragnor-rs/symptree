package com.symptree.messaging.core

import com.symptree.messaging.i18n.MessageCatalog
import com.symptree.messaging.port.ClinicAction
import com.symptree.messaging.port.ConclusionBodyDto
import com.symptree.messaging.port.GoalKey
import com.symptree.messaging.port.GoalPromptKind
import com.symptree.messaging.port.IngressOutcome
import com.symptree.messaging.port.OptionDto
import com.symptree.messaging.port.OutgoingAction
import com.symptree.messaging.port.RejectReason
import com.symptree.messaging.port.TestItemDto
import com.symptree.messaging.port.Urgency
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-VWRNDR / AC-VWRNDR.8c — **key coverage.** Every semantic outcome variant
 * (and every `ReminderKind`) must map to a catalog key that is backed in **every**
 * language bundle. We assert this behaviourally: render each variant against the
 * real catalog in both languages and verify no produced chrome string is an
 * unresolved key (`bot.*` marker) or leaks an interpolation token (`{`).
 */
class OutcomeKeyCoverageTest {

    private val catalog = MessageCatalog.loadFromClasspath()
    private val renderer = IngressRenderer(catalog)
    private val slotAt = Instant.parse("2026-07-10T09:00:00Z")

    private fun body(urgency: Urgency) = ConclusionBodyDto(
        urgency = urgency,
        diagnosis = "diag",
        action = "action",
        tests = listOf(TestItemDto("CBC", prep = "fasting")),
    )

    /** One representative instance of every semantic outcome variant. */
    private fun allOutcomes(): List<IngressOutcome> = buildList {
        add(IngressOutcome.Welcome(GoalKey.entries))
        add(IngressOutcome.LanguagePicker(listOf("ru", "kk")))
        GoalPromptKind.entries.forEach { add(IngressOutcome.GoalPrompt(it)) }
        add(IngressOutcome.NextQuestion("q1", "Q?", listOf(OptionDto("a", "A"))))
        // Reprompt: every reason, both the empty-options (→ goal menu) and
        // node-options (→ menu_header) branches.
        RejectReason.entries.forEach {
            add(IngressOutcome.Reprompt(it, text = null, options = emptyList()))
            add(IngressOutcome.Reprompt(it, text = "Q?", options = listOf(OptionDto("a", "A"))))
        }
        add(IngressOutcome.ButtonOnly("q1", "Q?", listOf(OptionDto("a", "A"))))
        // Conclusion: every clinic action × every urgency (covers the icon keys).
        ClinicAction.entries.forEach { action ->
            Urgency.entries.forEach { urgency -> add(IngressOutcome.Conclusion(body(urgency), action)) }
        }
        add(IngressOutcome.ConfirmSlot(body(Urgency.ROUTINE), "Dr A", slotAt, slotAt.plusSeconds(1800), "UTC"))
        add(IngressOutcome.ConfirmSlot(null, "Dr A", slotAt, slotAt.plusSeconds(1800), "UTC"))
        add(IngressOutcome.NoSlots(body(Urgency.ROUTINE)))
        add(IngressOutcome.BookingConfirmed("Dr A", slotAt, "UTC"))
        add(IngressOutcome.BookingContactClinic)
        add(IngressOutcome.NoAlgorithmMatch)
        add(IngressOutcome.LanguageChanged("kk"))
        add(IngressOutcome.LanguageNotAllowed("en"))
    }

    private fun chromeStrings(action: OutgoingAction): List<String> = when (action) {
        is OutgoingAction.SendText -> listOf(action.text)
        is OutgoingAction.SendMenu -> listOf(action.text) + action.options.map { it.label }
        else -> emptyList()
    }

    @Test
    fun `every outcome variant renders fully-backed chrome in every language bundle`() {
        for (lang in listOf("ru", "kk")) {
            for (outcome in allOutcomes()) {
                val strings = renderer.render(lang, outcome).flatMap { chromeStrings(it) }
                for (s in strings) {
                    assertFalse(
                        s.startsWith("bot."),
                        "[$lang] ${outcome::class.simpleName} produced an unresolved catalog key: '$s'",
                    )
                    assertFalse(
                        s.contains("{"),
                        "[$lang] ${outcome::class.simpleName} leaked an interpolation token: '$s'",
                    )
                    assertTrue(s.isNotBlank(), "[$lang] ${outcome::class.simpleName} produced a blank string")
                }
            }
        }
    }

    @Test
    fun `every reminder kind maps to a catalog key present in every bundle`() {
        // The frontend's kind→key contract (mirrors BotInternalOutgoingRoutes).
        val kindKeys = mapOf(
            "pre_visit_24h" to "bot.reminder.pre_visit_24h",
            "same_day" to "bot.reminder.same_day",
            "post_visit_feedback" to "bot.feedback.prompt",
        )
        for (lang in listOf("ru", "kk")) {
            for ((kind, key) in kindKeys) {
                assertTrue(catalog.has(key, lang), "reminder kind '$kind' → '$key' missing in [$lang] bundle")
            }
        }
    }
}
