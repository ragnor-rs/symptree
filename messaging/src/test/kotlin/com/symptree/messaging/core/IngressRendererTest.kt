package com.symptree.messaging.core

import com.symptree.messaging.i18n.MessageCatalog
import com.symptree.messaging.port.ClinicAction
import com.symptree.messaging.port.ConclusionBodyDto
import com.symptree.messaging.port.GoalKey
import com.symptree.messaging.port.IngressOutcome
import com.symptree.messaging.port.MenuLayout
import com.symptree.messaging.port.MenuOption
import com.symptree.messaging.port.OptionDto
import com.symptree.messaging.port.OutgoingAction
import com.symptree.messaging.port.RejectReason
import com.symptree.messaging.port.TestItemDto
import com.symptree.messaging.port.Urgency
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * FR-VWRNDR / AC-VWRNDR.2/.7 — **golden** frontend reassembly. Renders semantic
 * outcomes against the real unified catalog and pins the user-visible output
 * byte-for-byte against the pre-refactor strings (icon + diagnosis/action/tests
 * layout, datetime formatting, menu chrome). This is the behaviour-equivalence
 * guard: the same messages render; only *where* the chrome is assembled changed.
 */
class IngressRendererTest {

    private val renderer = IngressRenderer(MessageCatalog.loadFromClasspath())

    // 2026-07-10T09:00:00Z → 14:00 local in Asia/Almaty (UTC+5).
    private val slotAt = Instant.parse("2026-07-10T09:00:00Z")

    private fun text(action: OutgoingAction) = (action as OutgoingAction.SendText).text
    private fun menu(action: OutgoingAction) = action as OutgoingAction.SendMenu

    @Test
    fun `Welcome renders the greeting + a vertical goal menu with prefixed ids`() {
        val actions = renderer.render("ru", IngressOutcome.Welcome(GoalKey.entries))
        assertEquals("Здравствуйте! Я помогу разобраться с симптомами и записаться к врачу.", text(actions[0]))
        val m = menu(actions[1])
        assertEquals("Чем я могу вам помочь?", m.text)
        assertEquals(MenuLayout.VERTICAL, m.layout)
        assertEquals(
            listOf(
                MenuOption("goal:symptom_consultation", "🩺 Разобраться с симптомами"),
                MenuOption("goal:appointment_booking", "📅 Записаться к врачу"),
                MenuOption("goal:service_info", "ℹ️ Узнать об услугах"),
            ),
            m.options,
        )
    }

    @Test
    fun `Conclusion BOOK_APPOINTMENT assembles icon + body + tests block + booking CTA`() {
        val body = ConclusionBodyDto(
            urgency = Urgency.ROUTINE,
            diagnosis = "Нужна консультация",
            action = "Запишитесь к терапевту",
            tests = listOf(TestItemDto("ОАК", prep = "натощак"), TestItemDto("Глюкоза", prep = null)),
        )
        val actions = renderer.render("ru", IngressOutcome.Conclusion(body, ClinicAction.BOOK_APPOINTMENT))
        assertEquals(
            "📌 Нужна консультация\n\nЗапишитесь к терапевту\n\nПредварительные исследования:\n• ОАК\n  натощак\n• Глюкоза",
            text(actions[0]),
        )
        val m = menu(actions[1])
        assertEquals("Готовы записаться на приём?", m.text)
        assertEquals(listOf(MenuOption("cta:book", "Записаться сейчас")), m.options)
    }

    @Test
    fun `Conclusion BYPASS_APPOINTMENT appends the emergency line inline, no menu`() {
        val body = ConclusionBodyDto(Urgency.EMERGENCY, "Экстренно", "Вызовите 103", emptyList())
        val actions = renderer.render("ru", IngressOutcome.Conclusion(body, ClinicAction.BYPASS_APPOINTMENT))
        assertEquals(1, actions.size, "bypass renders a single SendText, no interactive menu")
        assertEquals(
            "🚨 Экстренно\n\nВызовите 103\n\nСрочно обратитесь в скорую помощь или ближайшее отделение.",
            text(actions[0]),
        )
    }

    @Test
    fun `ConfirmSlot formats the slot clinic-local and offers confirm-decline chrome`() {
        val actions = renderer.render(
            "ru",
            IngressOutcome.ConfirmSlot(
                body = null,
                doctorName = "Иванова",
                slotAt = slotAt,
                slotEnd = slotAt.plusSeconds(1800),
                clinicZone = "Asia/Almaty",
            ),
        )
        val m = menu(actions[0])
        assertEquals("Доктор Иванова — 10.07 14:00 — подтвердить запись?", m.text)
        assertEquals(
            listOf(
                MenuOption("booking:confirm", "✅ Да, записаться"),
                MenuOption("booking:decline", "🔄 Другое время"),
            ),
            m.options,
        )
    }

    @Test
    fun `BookingConfirmed formats the slot clinic-local`() {
        val actions = renderer.render(
            "ru",
            IngressOutcome.BookingConfirmed(doctorName = "Иванова", slotAt = slotAt, clinicZone = "Asia/Almaty"),
        )
        assertEquals("Готово! Вы записаны к Иванова на 10.07 14:00. Мы пришлём напоминание.", text(actions[0]))
    }

    @Test
    fun `Reprompt with node options re-renders the question above the same options`() {
        val actions = renderer.render(
            "ru",
            IngressOutcome.Reprompt(
                reason = RejectReason.OUT_OF_SET,
                text = "Болит сейчас?",
                options = listOf(OptionDto("yes", "Да"), OptionDto("no", "Нет")),
            ),
        )
        assertEquals(
            "Я не смог сопоставить ваш ответ с вариантами. Пожалуйста, выберите один из пунктов меню.",
            text(actions[0]),
        )
        val m = menu(actions[1])
        assertEquals("Болит сейчас?", m.text)
        assertEquals(listOf(MenuOption("yes", "Да"), MenuOption("no", "Нет")), m.options)
    }

    @Test
    fun `Reprompt with no options re-offers the goal menu`() {
        val actions = renderer.render(
            "ru",
            IngressOutcome.Reprompt(reason = RejectReason.NON_SYMPTOM_INTENT, text = null, options = emptyList()),
        )
        assertEquals(
            "Похоже, ваш вопрос не про симптомы. Если хотите продолжить, выберите вариант из меню.",
            text(actions[0]),
        )
        val m = menu(actions[1])
        assertEquals("Чем я могу вам помочь?", m.text)
        assertEquals(3, m.options.size, "the welcome goal menu is re-offered")
    }

    @Test
    fun `ButtonOnly prepends the llm-unavailable notice then the node menu`() {
        val actions = renderer.render(
            "ru",
            IngressOutcome.ButtonOnly("q1", "Болит сейчас?", listOf(OptionDto("yes", "Да"))),
        )
        assertEquals("Сейчас я не могу разобрать ответ. Пожалуйста, выберите вариант из меню ниже.", text(actions[0]))
        assertEquals("Болит сейчас?", menu(actions[1]).text)
    }

    @Test
    fun `LanguageChanged renders the confirmation in the NEW language`() {
        val actions = renderer.render("ru", IngressOutcome.LanguageChanged("kk"))
        // Rendered in kk (the new language), with the language name in kk.
        assertEquals("Тіл Қазақша тіліне ауыстырылды.", text(actions[0]))
    }
}
