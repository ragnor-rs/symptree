package com.symptree.core.symptomizer

import com.symptree.core.llm.LlmRejectedException
import java.time.Instant

/**
 * Bot-facing result of a single `SessionService.applyStep` invocation.
 *
 * The orchestrator (`SessionService`) translates one user turn into exactly one
 * of these prompt variants; the bot adapter is responsible for rendering it on
 * the platform of its choice (Telegram inline keyboard, WhatsApp interactive
 * list, etc.).
 *
 * - [NextPrompt] — the walker advanced to a new question node. The bot should
 *   show `text[<lang>]` and the labelled `options`.
 * - [ConclusionPrompt] — the walker reached a leaf [ConclusionNode]. The bot
 *   should render the diagnosis/action/next-step text and end the session.
 * - [RepromptPrompt] — the LLM returned an option mapping that failed
 *   `RouterValidator` (out-of-set, low-confidence, or null pick). The bot
 *   should re-show the same node's option list along with an optional
 *   clarifying nudge driven by [reason].
 * - [ButtonOnlyPrompt] — the LLM upstream was unavailable (threw). The bot
 *   should re-show the node's option list and require a tap, with no LLM
 *   retries within this request — see AC-PWVTT4.9.
 */
sealed interface StepResult {

    data class NextPrompt(
        val nodeId: String,
        val text: Map<String, String>,
        val options: List<PromptOption>,
    ) : StepResult

    data class ConclusionPrompt(
        val node: ConclusionNode,
    ) : StepResult

    data class RepromptPrompt(
        val nodeId: String,
        val reason: LlmRejectedException.Reason,
        val options: List<PromptOption>,
        /**
         * Current node's question text (i18n map). The bot should re-render
         * this above the option list so the user sees what they're answering;
         * a contextless menu after a rejected free-form reply is incoherent UX.
         * Null on pre-pick rejections (no node yet — the bot falls back to the
         * welcome menu in that branch).
         */
        val text: Map<String, String>? = null,
    ) : StepResult

    data class ButtonOnlyPrompt(
        val nodeId: String,
        val text: Map<String, String>,
        val options: List<PromptOption>,
    ) : StepResult

    /**
     * AC-PWVTT4.8 — the LLM router could not match the user's complaint to
     * any of the clinic's enabled algorithms. The bot should surface a
     * "we don't have a flow for that yet" message and re-offer the welcome
     * menu so the user can try a different complaint OR switch to booking.
     */
    data object NoAlgorithmMatch : StepResult

    /**
     * AC-W03362.5 — a `BOOK_APPOINTMENT` conclusion auto-assigned the earliest
     * slot. The bot renders the conclusion body (when [node] is non-null — the
     * first proposal) plus a `bot.booking.confirm_slot` prompt with yes/no
     * buttons. On a re-proposal (after a decline or a lost race) [node] is null
     * and only the confirm prompt is shown.
     */
    data class ConfirmSlot(
        val node: ConclusionNode?,
        val doctorDisplayName: String,
        val slotAt: Instant,
        val slotEnd: Instant,
    ) : StepResult

    /**
     * AC-W03362.5 — a `BOOK_APPOINTMENT` conclusion was reached but no slot is
     * available in the lookahead window. The bot renders the conclusion body
     * plus `bot.booking.no_slots`; the session is concluded.
     */
    data class NoSlots(val node: ConclusionNode) : StepResult

    /**
     * AC-W03362.6 — the patient confirmed: the booking landed. The bot sends
     * `bot.booking.confirmed`; the session is concluded.
     */
    data class BookingConfirmed(
        val doctorDisplayName: String,
        val slotAt: Instant,
    ) : StepResult

    /**
     * AC-W03362.7 — the patient declined past the cap (or no slot remained on a
     * race). The bot sends `bot.booking.contact_clinic`; the session is concluded.
     */
    data object BookingContactClinic : StepResult
}

/**
 * Platform-neutral option payload for the bot adapter. Mirrors the shape of
 * the underlying [Option] (value + multilingual label) but lives in the prompt
 * layer so adapters never depend on the algorithm internals.
 */
data class PromptOption(
    val value: String,
    val label: Map<String, String>,
)
