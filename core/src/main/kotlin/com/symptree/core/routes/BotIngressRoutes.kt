package com.symptree.core.routes

import com.symptree.core.db.ClinicRepo
import com.symptree.core.llm.LlmRejectedException
import com.symptree.core.symptomizer.ConclusionNode
import com.symptree.core.symptomizer.LanguageNotAllowedException
import com.symptree.core.symptomizer.PromptOption
import com.symptree.core.symptomizer.SessionService
import com.symptree.core.symptomizer.StepResult
import com.symptree.core.model.Clinic
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class BotIngressErrorResponse(val error: String, val detail: String? = null)

private const val BOT_INGRESS_SECRET_HEADER = "X-API-Key"

/** The clinic's enabled goals offered on the welcome menu (M2: a fixed set). */
private val WELCOME_GOALS = listOf(
    GoalKey.SYMPTOM_CONSULTATION,
    GoalKey.APPOINTMENT_BOOKING,
    GoalKey.SERVICE_INFO,
)

/**
 * AC-W3WDWH.2 / FR-VWRNDR — mounts `POST /api/v1/clinics/{slug}/bot/ingress`.
 *
 * Contract (FR-VWRNDR): the response is a [IngressResponseDto] carrying the
 * resolved session language plus a list of **semantic outcome DTOs**. `core`
 * emits *what happened* as typed, data-holding fields and localizes only its own
 * algorithm-tree text; the `messaging` frontend owns every view concern (chrome
 * strings, icons, datetime formatting, layout). No localization key, catalog
 * key, or finished chrome string crosses this wire.
 *
 *  - Auth: shared-secret via the `X-API-Key` header. Missing/wrong → 401.
 *  - `/start` or empty payload → [IngressOutcomeDto.Welcome].
 *  - `callbackData = "goal:<id>"` → [IngressOutcomeDto.GoalPrompt].
 *  - Any other text → drives `SessionService.applyStep` and maps its
 *    [StepResult] to one semantic [IngressOutcomeDto].
 *
 * State mutation stays in `SessionService`; this layer is pure translation + I/O.
 */
fun Route.botIngressRoutes(
    clinicRepo: ClinicRepo,
    sessionService: SessionService,
    botIngressSharedSecret: String,
) {
    route("/api/v1/clinics/{slug}/bot/ingress") {
        post {
            val presented = call.request.headers[BOT_INGRESS_SECRET_HEADER]
            if (presented.isNullOrBlank() || presented != botIngressSharedSecret) {
                call.respond(HttpStatusCode.Unauthorized, BotIngressErrorResponse("unauthorized"))
                return@post
            }

            val slug = call.parameters["slug"].orEmpty()
            val request = try {
                call.receive<IngressRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    BotIngressErrorResponse("invalid_body", detail = e.message),
                )
                return@post
            }

            val clinic = clinicRepo.findBySlug(slug)
            if (clinic == null) {
                call.respond(HttpStatusCode.NotFound, BotIngressErrorResponse("clinic_not_found"))
                return@post
            }

            // AC-W3WDWH.10 — post-conclusion behavior: if the user's last
            // session is `concluded` and they have no active session, treat
            // free-text or a fresh callback as a return-to-start (welcome menu
            // in their last language) rather than auto-opening a brand new
            // symptomizer journey.
            val latest = sessionService.findLatestSession(
                clinicId = clinic.id,
                platform = request.user.platform,
                platformUserId = request.user.id,
            )
            val isPostConclusion = latest != null && latest.status == "concluded"
            val isLanguageOp = request.text == "/language" ||
                request.callbackData?.startsWith("lang:") == true
            if (isPostConclusion && request.text != "/start" &&
                request.callbackData?.startsWith("goal:") != true &&
                !isLanguageOp
            ) {
                call.respond(HttpStatusCode.OK, response(latest.language, IngressOutcomeDto.Welcome(WELCOME_GOALS)))
                return@post
            }

            val session = sessionService.startOrResume(
                clinicId = clinic.id,
                platform = request.user.platform,
                platformUserId = request.user.id,
                // Persist the user's chosen language across sessions: a new
                // session inherits the language of their last session (set via
                // /language), falling back to the client locale only for a
                // brand-new user.
                languageHint = latest?.language ?: request.user.languageCode,
            )
            val lang = session.language

            // AC-W03362.5/.6/.7 — a session awaiting booking confirmation reads
            // this turn as a confirm/decline response. `/start` still escapes.
            val pending = sessionService.pendingBooking(session)
            if (pending != null && request.text != "/start") {
                val result = when (request.callbackData) {
                    "booking:confirm" -> sessionService.confirmPendingBooking(session)
                    "booking:decline" -> sessionService.declinePendingBooking(session)
                    else -> StepResult.ConfirmSlot(
                        null, pending.doctorDisplayName, pending.slotAt, pending.slotEnd,
                    )
                }
                call.respond(HttpStatusCode.OK, response(lang, renderStepResult(result, lang, clinicZone(clinic))))
                return@post
            }

            val outcome: IngressOutcomeDto = when {
                request.text == "/start" -> {
                    // Hard reset: cancel any active session so the next user
                    // message creates a fresh one.
                    if (session.currentAlgorithmId != null) {
                        sessionService.cancelActive(session.id)
                    }
                    IngressOutcomeDto.Welcome(WELCOME_GOALS)
                }
                request.text == "/language" -> IngressOutcomeDto.LanguagePicker(clinic.allowedLanguages)
                request.callbackData?.startsWith("goal:") == true -> {
                    val goalId = request.callbackData.removePrefix("goal:")
                    // Re-picking "symptom_consultation" mid-walk is an explicit
                    // restart intent — cancel the active session.
                    if (goalId == "symptom_consultation" && session.currentAlgorithmId != null) {
                        sessionService.cancelActive(session.id)
                    }
                    handleGoalSelection(goalId)
                }
                request.callbackData?.startsWith("lang:") == true -> handleLanguageSwitch(
                    sessionId = session.id,
                    requestedLang = request.callbackData.removePrefix("lang:"),
                    sessionService = sessionService,
                )
                request.callbackData?.startsWith("cta:") == true ->
                    // Booking-DISABLED fallback only (unreachable in the pilot,
                    // where BOOK_APPOINTMENT surfaces as ConfirmSlot). Same
                    // user-visible string as the booking goal stub.
                    IngressOutcomeDto.GoalPrompt(GoalPromptKind.BOOKING_COMING_SOON)
                request.text.isNullOrBlank() && request.callbackData.isNullOrBlank() ->
                    IngressOutcomeDto.Welcome(WELCOME_GOALS)
                else -> {
                    val userText = request.text ?: request.callbackData.orEmpty()
                    renderStepResult(sessionService.applyStep(session, userText), lang, clinicZone(clinic))
                }
            }

            call.respond(HttpStatusCode.OK, response(lang, outcome))
        }
    }
}

private fun response(lang: String, outcome: IngressOutcomeDto): IngressResponseDto =
    IngressResponseDto(lang = lang, outcomes = listOf(outcome))

/** AC-W3WDWH.5 (entry) — map a `goal:<id>` callback to its semantic prompt. */
private fun handleGoalSelection(goalId: String): IngressOutcomeDto = IngressOutcomeDto.GoalPrompt(
    when (goalId) {
        "symptom_consultation" -> GoalPromptKind.MAIN_COMPLAINT
        "appointment_booking" -> GoalPromptKind.BOOKING_COMING_SOON
        "service_info" -> GoalPromptKind.SERVICE_INFO_COMING_SOON
        else -> GoalPromptKind.UNKNOWN_GOAL
    },
)

/**
 * AC-W3WDWH.3 — handle a `lang:<code>` callback. Switches the session's language
 * via [SessionService.setLanguage] (which enforces `allowed_languages`) and emits
 * the semantic switch outcome; `messaging` renders the confirmation in the new
 * language.
 */
private fun handleLanguageSwitch(
    sessionId: Long,
    requestedLang: String,
    sessionService: SessionService,
): IngressOutcomeDto = try {
    val updated = sessionService.setLanguage(sessionId, requestedLang)
    IngressOutcomeDto.LanguageChanged(updated.language)
} catch (e: LanguageNotAllowedException) {
    IngressOutcomeDto.LanguageNotAllowed(requestedLang)
}

/**
 * AC-W3WDWH.5/.6/.7 + FR-VWRNDR — map a [StepResult] to one semantic outcome.
 * Algorithm-tree text is localized here ([localize]); chrome stays in `messaging`.
 */
private fun renderStepResult(
    result: StepResult,
    lang: String,
    zone: String = "UTC",
): IngressOutcomeDto = when (result) {
    is StepResult.NextPrompt -> IngressOutcomeDto.NextQuestion(
        nodeId = result.nodeId,
        text = localize(result.text, lang),
        options = result.options.toOptionDtos(lang),
    )
    is StepResult.ButtonOnlyPrompt -> IngressOutcomeDto.ButtonOnly(
        nodeId = result.nodeId,
        text = localize(result.text, lang),
        options = result.options.toOptionDtos(lang),
    )
    is StepResult.RepromptPrompt -> IngressOutcomeDto.Reprompt(
        reason = result.reason.toWire(),
        text = result.text?.let { localize(it, lang) },
        options = result.options.toOptionDtos(lang),
    )
    is StepResult.ConclusionPrompt ->
        IngressOutcomeDto.Conclusion(conclusionBody(result.node, lang), result.node.clinicAction)
    is StepResult.NoAlgorithmMatch -> IngressOutcomeDto.NoAlgorithmMatch
    is StepResult.ConfirmSlot -> IngressOutcomeDto.ConfirmSlot(
        body = result.node?.let { conclusionBody(it, lang) },
        doctorName = result.doctorDisplayName,
        slotAt = result.slotAt,
        slotEnd = result.slotEnd,
        clinicZone = zone,
    )
    is StepResult.NoSlots -> IngressOutcomeDto.NoSlots(conclusionBody(result.node, lang))
    is StepResult.BookingConfirmed -> IngressOutcomeDto.BookingConfirmed(
        doctorName = result.doctorDisplayName,
        slotAt = result.slotAt,
        clinicZone = zone,
    )
    StepResult.BookingContactClinic -> IngressOutcomeDto.BookingContactClinic
}

/**
 * Build the localized conclusion body (urgency + diagnosis + action + tests) as
 * concrete fields. The clinic-action CTA and all chrome (icon, labels, layout)
 * are the frontend's job. Tests are `tests` then `additionalTests`, each with its
 * localized prep note when `ConclusionNode.preparation` has one.
 */
private fun conclusionBody(node: ConclusionNode, lang: String): ConclusionBodyDto {
    val tests = (node.tests + node.additionalTests).map { name ->
        val prep = node.preparation[name]?.let { localize(it, lang) }?.takeIf { it.isNotBlank() }
        TestItemDto(name = name, prep = prep)
    }
    return ConclusionBodyDto(
        urgency = node.urgency,
        diagnosis = localize(node.diagnosis, lang),
        action = localize(node.action, lang),
        tests = tests,
    )
}

/** Read the clinic's IANA display timezone from `config.preferences.timezone` (default `UTC`). */
private fun clinicZone(clinic: Clinic): String =
    runCatching {
        clinic.config["preferences"]?.jsonObject?.get("timezone")?.jsonPrimitive?.content
    }.getOrNull() ?: "UTC"

private fun LlmRejectedException.Reason.toWire(): RejectReason = when (this) {
    LlmRejectedException.Reason.OUT_OF_SET -> RejectReason.OUT_OF_SET
    LlmRejectedException.Reason.LOW_CONFIDENCE -> RejectReason.LOW_CONFIDENCE
    LlmRejectedException.Reason.NULL_PICK -> RejectReason.NULL_PICK
    LlmRejectedException.Reason.NON_SYMPTOM_INTENT -> RejectReason.NON_SYMPTOM_INTENT
}

private fun List<PromptOption>.toOptionDtos(lang: String): List<OptionDto> =
    map { OptionDto(value = it.value, label = localize(it.label, lang)) }

private fun localize(map: Map<String, String>, lang: String): String =
    map[lang] ?: map["en"] ?: map.values.firstOrNull() ?: ""
