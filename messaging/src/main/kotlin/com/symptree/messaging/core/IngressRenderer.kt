package com.symptree.messaging.core

import com.symptree.messaging.i18n.MessageCatalog
import com.symptree.messaging.i18n.SlotFormatter
import com.symptree.messaging.port.ClinicAction
import com.symptree.messaging.port.ConclusionBodyDto
import com.symptree.messaging.port.GoalKey
import com.symptree.messaging.port.GoalPromptKind
import com.symptree.messaging.port.IngressOutcome
import com.symptree.messaging.port.MenuLayout
import com.symptree.messaging.port.MenuOption
import com.symptree.messaging.port.OptionDto
import com.symptree.messaging.port.OutgoingAction

/**
 * FR-VWRNDR — the frontend's view layer. Maps each semantic [IngressOutcome] from
 * `core` to one or more platform-neutral [OutgoingAction]s, sourcing **all**
 * chrome (welcome/menu/reprompt strings, urgency icons, "tests:" label, datetime
 * formatting, layout) from the single [MessageCatalog]. `core`'s already-localized
 * domain text (question/option/diagnosis/action/test strings) is displayed
 * verbatim and never looked up in the catalog (AC-VWRNDR.3).
 */
class IngressRenderer(private val catalog: MessageCatalog) {

    /** Render every outcome in a response, in order, into a flat action list. */
    fun render(lang: String, outcomes: List<IngressOutcome>): List<OutgoingAction> =
        outcomes.flatMap { render(lang, it) }

    @Suppress("CyclomaticComplexMethod")
    fun render(lang: String, outcome: IngressOutcome): List<OutgoingAction> = when (outcome) {
        is IngressOutcome.Welcome -> listOf(
            OutgoingAction.SendText(catalog.get("bot.welcome.text", lang)),
            goalMenu(lang, outcome.goals),
        )
        is IngressOutcome.LanguagePicker -> listOf(
            OutgoingAction.SendMenu(
                text = catalog.get("bot.language.prompt", lang),
                options = outcome.allowed.map { code ->
                    MenuOption(id = "lang:$code", label = catalog.get("bot.language.option.$code", lang))
                },
                layout = MenuLayout.VERTICAL,
            ),
        )
        is IngressOutcome.GoalPrompt -> listOf(
            OutgoingAction.SendText(catalog.get(goalPromptKey(outcome.prompt), lang)),
        )
        is IngressOutcome.NextQuestion -> listOf(
            OutgoingAction.SendMenu(
                text = outcome.text,
                options = outcome.options.toMenuOptions(),
                layout = MenuLayout.VERTICAL,
            ),
        )
        is IngressOutcome.ButtonOnly -> listOf(
            OutgoingAction.SendText(catalog.get("bot.symptom.llm_unavailable", lang)),
            OutgoingAction.SendMenu(
                text = outcome.text,
                options = outcome.options.toMenuOptions(),
                layout = MenuLayout.VERTICAL,
            ),
        )
        is IngressOutcome.Reprompt -> {
            val reprompt = OutgoingAction.SendText(catalog.get("bot.symptom.reprompt.${outcome.reason.wire}", lang))
            if (outcome.options.isEmpty()) {
                // Pre-pick rejection: no node yet — re-offer the goal menu.
                listOf(reprompt, goalMenu(lang, MENU_GOALS))
            } else {
                val menuText = outcome.text ?: catalog.get("bot.symptom.reprompt.menu_header", lang)
                listOf(
                    reprompt,
                    OutgoingAction.SendMenu(menuText, outcome.options.toMenuOptions(), MenuLayout.VERTICAL),
                )
            }
        }
        is IngressOutcome.Conclusion -> renderConclusion(lang, outcome)
        is IngressOutcome.ConfirmSlot -> {
            val datetime = SlotFormatter.datetime(outcome.slotAt, outcome.clinicZone)
            val body = outcome.body?.let { listOf(OutgoingAction.SendText(conclusionBodyText(lang, it))) }.orEmpty()
            body + OutgoingAction.SendMenu(
                text = catalog.get(
                    "bot.booking.confirm_slot",
                    lang,
                    mapOf("doctor" to outcome.doctorName, "datetime" to datetime),
                ),
                options = listOf(
                    MenuOption(id = "booking:confirm", label = catalog.get("bot.booking.confirm_yes", lang)),
                    MenuOption(id = "booking:decline", label = catalog.get("bot.booking.confirm_no", lang)),
                ),
                layout = MenuLayout.VERTICAL,
            )
        }
        is IngressOutcome.NoSlots -> listOf(
            OutgoingAction.SendText(conclusionBodyText(lang, outcome.body)),
            OutgoingAction.SendText(catalog.get("bot.booking.no_slots", lang)),
        )
        is IngressOutcome.BookingConfirmed -> listOf(
            OutgoingAction.SendText(
                catalog.get(
                    "bot.booking.confirmed",
                    lang,
                    mapOf(
                        "doctor" to outcome.doctorName,
                        "datetime" to SlotFormatter.datetime(outcome.slotAt, outcome.clinicZone),
                    ),
                ),
            ),
        )
        IngressOutcome.BookingContactClinic -> listOf(
            OutgoingAction.SendText(catalog.get("bot.booking.contact_clinic", lang)),
        )
        IngressOutcome.NoAlgorithmMatch -> listOf(
            OutgoingAction.SendText(catalog.get("bot.symptom.no_algorithm_match", lang)),
            goalMenu(lang, MENU_GOALS),
        )
        is IngressOutcome.LanguageChanged -> listOf(
            // Confirm in the NEW language so the user can verify the switch landed.
            OutgoingAction.SendText(
                catalog.get(
                    "bot.language.changed",
                    outcome.language,
                    mapOf("language" to catalog.get("bot.language.option.${outcome.language}", outcome.language)),
                ),
            ),
        )
        is IngressOutcome.LanguageNotAllowed -> listOf(
            OutgoingAction.SendText(
                catalog.get("bot.language.not_allowed", lang, mapOf("language" to outcome.requested)),
            ),
        )
    }

    private fun renderConclusion(lang: String, outcome: IngressOutcome.Conclusion): List<OutgoingAction> {
        val body = conclusionBodyText(lang, outcome.body)
        return when (outcome.clinicAction) {
            // Booking-DISABLED fallback only (the live pilot path is ConfirmSlot).
            ClinicAction.BOOK_APPOINTMENT -> listOf(
                OutgoingAction.SendText(body),
                OutgoingAction.SendMenu(
                    text = catalog.get("bot.conclusion.cta.book_prompt", lang),
                    options = listOf(
                        MenuOption(id = "cta:book", label = catalog.get("bot.conclusion.cta.book", lang)),
                    ),
                    layout = MenuLayout.VERTICAL,
                ),
            )
            ClinicAction.BYPASS_APPOINTMENT -> listOf(
                OutgoingAction.SendText(body + "\n\n" + catalog.get("bot.conclusion.cta.emergency", lang)),
            )
            ClinicAction.RECOMMEND_PHARMACY -> listOf(
                OutgoingAction.SendText(body + "\n\n" + catalog.get("bot.conclusion.cta.pharmacy", lang)),
            )
            ClinicAction.RECOMMEND_SELF_CARE -> listOf(
                OutgoingAction.SendText(body + "\n\n" + catalog.get("bot.conclusion.cta.self_care", lang)),
            )
        }
    }

    /**
     * Assemble the conclusion body: urgency icon + diagnosis, action, then the
     * tests block (label + bulleted names with optional prep). Domain strings are
     * `core`'s; only the icon and the "tests:" label come from the catalog.
     */
    private fun conclusionBodyText(lang: String, body: ConclusionBodyDto): String = buildString {
        append(catalog.get("bot.conclusion.urgency.${body.urgency.wire}", lang)).append(' ').append(body.diagnosis)
        append("\n\n").append(body.action)
        if (body.tests.isNotEmpty()) {
            append("\n\n").append(catalog.get("bot.conclusion.tests", lang)).append(":")
            for (test in body.tests) {
                append("\n• ").append(test.name)
                if (!test.prep.isNullOrBlank()) append("\n  ").append(test.prep)
            }
        }
    }

    private fun goalMenu(lang: String, goals: List<GoalKey>): OutgoingAction.SendMenu =
        OutgoingAction.SendMenu(
            text = catalog.get("bot.welcome.menu_prompt", lang),
            options = goals.map { goal ->
                MenuOption(id = "goal:${goal.wire}", label = catalog.get("bot.welcome.goal.${goal.wire}", lang))
            },
            layout = MenuLayout.VERTICAL,
        )

    private fun List<OptionDto>.toMenuOptions(): List<MenuOption> =
        map { MenuOption(id = it.value, label = it.label) }

    private fun goalPromptKey(prompt: GoalPromptKind): String = when (prompt) {
        GoalPromptKind.MAIN_COMPLAINT -> "bot.symptom.prompt_main_complaint"
        GoalPromptKind.BOOKING_COMING_SOON -> "bot.booking.coming_soon"
        GoalPromptKind.SERVICE_INFO_COMING_SOON -> "bot.faq.coming_soon"
        GoalPromptKind.UNKNOWN_GOAL -> "bot.welcome.unknown_goal"
    }

    private companion object {
        /** The fixed goal set re-offered on the "menu only" chrome re-prompts. */
        val MENU_GOALS = listOf(
            GoalKey.SYMPTOM_CONSULTATION,
            GoalKey.APPOINTMENT_BOOKING,
            GoalKey.SERVICE_INFO,
        )
    }
}
