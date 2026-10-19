package com.symptree.core.symptomizer

import com.symptree.core.booking.BookingOutcome
import com.symptree.core.booking.BookingService
import com.symptree.core.crypto.PiiCipher
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.PendingBooking
import com.symptree.core.db.PendingBookingRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.llm.AlgorithmCandidate
import com.symptree.core.source.PatientRef
import com.symptree.core.llm.Intent
import com.symptree.core.llm.LlmRejectedException
import com.symptree.core.llm.LlmRouterClient
import com.symptree.core.llm.RouterValidator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration
import java.time.Instant

/**
 * Orchestrates one bot user's symptomizer journey: starts or resumes a
 * session, applies a language override, and drives one walker step per user
 * turn (intent classification → option mapping → walker advance → history
 * append + state update in a single DB UPDATE).
 *
 * AC-PWVTT4.2 (this file): [startOrResume].
 * AC-PWVTT4.3 (later fork): [setLanguage].
 * AC-PWVTT4.8 / .9 (later forks): [applyStep] including the
 * `ButtonOnlyPrompt` fallback when the LLM upstream throws.
 *
 * Dependencies are wired by hand from `main()` per CLAUDE.md "no DI" rule.
 */
class SessionService(
    private val clinicRepo: ClinicRepo,
    private val algorithmRepo: AlgorithmRepo,
    private val sessionRepo: SessionRepo,
    private val llm: LlmRouterClient,
    private val piiCipher: PiiCipher,
    /**
     * AC-W03362.5 — builds a clinic-scoped [BookingService] on demand for the
     * session's `clinicId`. Null disables auto-booking (a `BOOK_APPOINTMENT`
     * conclusion then falls back to the plain [StepResult.ConclusionPrompt]),
     * which keeps every pre-W03362 construction site working unchanged.
     */
    private val bookingServiceFactory: ((Long) -> BookingService)? = null,
    private val pendingBookingRepo: PendingBookingRepo? = null,
) {

    /**
     * AC-W3WDWH.10 — returns the most-recent session row (any status) for
     * `(clinic, platform, platform_user_id)`, or `null` if the user has never
     * started a session. Used by the bot ingress route to detect a freshly-
     * concluded conversation so it can route subsequent free-text to the
     * welcome menu instead of opening a brand new symptomizer journey.
     */
    fun findLatestSession(clinicId: Long, platform: String, platformUserId: String): Session? =
        sessionRepo.findLatestSession(clinicId, platform, platformUserId)

    /**
     * Return the existing active session for `(clinicId, platform,
     * platformUserId)`, or create a new one.
     *
     * The `(clinic_id, platform, platform_user_id, status='active')` UNIQUE
     * constraint on `sessions` guarantees at most one active row per tuple,
     * so the lookup is exact.
     *
     * Language resolution at creation time:
     *  1. If [languageHint] is non-null AND in the clinic's `allowed_languages`,
     *     use it.
     *  2. Otherwise fall back to the clinic's `default_language`.
     *
     * An existing active session is returned as-is — language overrides only
     * land via [setLanguage] (AC-PWVTT4.3).
     */
    fun startOrResume(
        clinicId: Long,
        platform: String,
        platformUserId: String,
        languageHint: String? = null,
    ): Session {
        sessionRepo.findActiveSession(clinicId, platform, platformUserId)?.let { return it }

        val clinic = clinicRepo.findById(clinicId)
            ?: error("clinic $clinicId not found while starting a session")
        val language = resolveStartLanguage(languageHint, clinic.allowedLanguages, clinic.defaultLanguage)

        val newId = sessionRepo.insertActive(
            clinicId = clinicId,
            platform = platform,
            platformUserId = platformUserId,
            language = language,
        )
        return sessionRepo.findById(newId)
            ?: error("session $newId vanished immediately after insert")
    }

    /**
     * Cancel an active session by id. Used by the bot route when the user
     * explicitly resets the flow (`/start` text or re-picking the
     * `symptom_consultation` goal mid-walk) — flipping the row to
     * `status = 'cancelled'` lets `startOrResume` create a fresh active session
     * on the next inbound message, without leaving the user stuck behind a
     * half-completed walker state.
     */
    fun cancelActive(sessionId: Long): Int = sessionRepo.cancelActive(sessionId)

    /**
     * AC-PWVTT4.3 — switch the active language for an existing session.
     *
     * Steps:
     *  1. Load the session by id; if missing, raise [IllegalArgumentException].
     *  2. Load the owning clinic; if missing, raise [IllegalStateException]
     *     (foreign-key violation that shouldn't happen at runtime).
     *  3. Reject with [LanguageNotAllowedException] if `language` is not in
     *     the clinic's `allowed_languages` set.
     *  4. Atomically persist the new language and append a single audit turn
     *     (`{"kind":"language_change","from":...,"to":...,"ts":...}`) to
     *     `sessions.history` in one UPDATE.
     *  5. Return the reloaded [Session].
     */
    fun setLanguage(sessionId: Long, language: String): Session {
        val session = sessionRepo.findById(sessionId)
            ?: throw IllegalArgumentException("session $sessionId not found")
        val clinic = clinicRepo.findById(session.clinicId)
            ?: error("clinic ${session.clinicId} not found for session $sessionId")
        if (language !in clinic.allowedLanguages) {
            throw LanguageNotAllowedException(language, clinic.allowedLanguages)
        }
        val turn = buildJsonObject {
            put("kind", JsonPrimitive("language_change"))
            put("from", JsonPrimitive(session.language))
            put("to", JsonPrimitive(language))
            put("ts", JsonPrimitive(Instant.now().toString()))
        }
        sessionRepo.updateLanguageAndAppendTurn(sessionId, language, turn.toString())
        return sessionRepo.findById(sessionId)
            ?: error("session $sessionId vanished immediately after language update")
    }

    /**
     * AC-PWVTT4.8 / .9 — drives a single walker step.
     *
     * Pre-pick (`session.currentAlgorithmId == null`):
     *  1. Classify intent via [llm]; validate with [RouterValidator.validateIntent].
     *  2. On rejection, return [StepResult.RepromptPrompt] and record the
     *     rejected attempt in `history`.
     *  3. On `Intent.SYMPTOM`, ask the [llm] to select the best-matching enabled
     *     algorithm and validate the pick with
     *     [RouterValidator.validateAlgorithmSelection] against the
     *     [ALGORITHM_SELECTION_CONFIDENCE] floor. On a confident match, load its
     *     tree and advance the session to the algorithm's entry node, returning
     *     [StepResult.NextPrompt] carrying the entry node's text + options; on no
     *     confident match, return [StepResult.NoAlgorithmMatch] (a reprompt).
     *
     * Mid-walk (`currentAlgorithmId != null && currentNodeId != null`):
     *  1. Load the algorithm tree, locate the current [QuestionNode].
     *  2. Call [LlmRouterClient.mapFreeFormToOption]; if it throws, fall back
     *     to [StepResult.ButtonOnlyPrompt] (AC-PWVTT4.9 — no retries within a
     *     single request).
     *  3. Validate via [RouterValidator.validateOption]. On rejection, return
     *     [StepResult.RepromptPrompt].
     *  4. On success, advance the walker. If the result is another question,
     *     return [StepResult.NextPrompt]; if a conclusion, flip the session to
     *     `'concluded'` and return [StepResult.ConclusionPrompt].
     *
     * Every transition writes state and history in a single atomic UPDATE via
     * [SessionRepo.updateStateAndAppendTurn].
     */
    suspend fun applyStep(session: Session, userText: String): StepResult =
        if (session.currentAlgorithmId == null || session.currentNodeId == null) {
            applyPrePickStep(session, userText)
        } else {
            applyMidWalkStep(session, userText)
        }

    private suspend fun applyPrePickStep(session: Session, userText: String): StepResult {
        val availableIntents = setOf(Intent.SYMPTOM, Intent.BOOKING, Intent.FAQ, Intent.FALLBACK)
        val classification = llm.classifyIntent(
            userMessage = userText,
            language = session.language,
            availableIntents = availableIntents,
        )
        val intent = try {
            RouterValidator.validateIntent(classification, availableIntents)
        } catch (rejected: LlmRejectedException) {
            appendTurn(
                session = session,
                turn = auditTurn(
                    kind = "intent_rejected",
                    nodeId = "",
                    userText = userText,
                    extras = mapOf("rejection_reason" to rejected.reason.name),
                ),
            )
            return StepResult.RepromptPrompt(
                nodeId = "",
                reason = rejected.reason,
                options = emptyList(),
            )
        }
        if (intent != Intent.SYMPTOM) {
            // M2: only SYMPTOM drives the walker; BOOKING / FAQ / FALLBACK are
            // routed back to the bot with a dedicated reason so it can render
            // a different UX (e.g. "want to book?" vs a hallucination reprompt).
            appendTurn(
                session = session,
                turn = auditTurn(
                    kind = "intent_non_symptom",
                    nodeId = "",
                    userText = userText,
                    extras = mapOf("llm_intent" to intent.name),
                ),
            )
            return StepResult.RepromptPrompt(
                nodeId = "",
                reason = LlmRejectedException.Reason.NON_SYMPTOM_INTENT,
                options = emptyList(),
            )
        }
        // AC-PWVTT4.8 — LLM-driven algorithm selection from the clinic's
        // enabled set. Returns null when no algorithm reasonably matches the
        // complaint; the bot surfaces a no-match message in that case.
        val enabledIds = clinicRepo.findEnabledAlgorithmIds(session.clinicId)
        if (enabledIds.isEmpty()) {
            error("clinic ${session.clinicId} has no enabled algorithms for SYMPTOM intent")
        }
        val candidates = enabledIds.mapNotNull { id ->
            val algo = algorithmRepo.findById(id) ?: return@mapNotNull null
            val displayName = algo.metadata.name[session.language]
                ?: algo.metadata.name["ru"]
                ?: algo.metadata.name.values.firstOrNull().orEmpty()
            AlgorithmCandidate(
                id = id,
                displayName = displayName,
                category = algo.metadata.category,
            )
        }
        val selection = llm.selectAlgorithm(
            userMessage = userText,
            language = session.language,
            candidates = candidates,
        )
        val selectedId = RouterValidator.validateAlgorithmSelection(
            selection = selection,
            candidateIds = candidates.map { it.id }.toSet(),
            confidenceThreshold = ALGORITHM_SELECTION_CONFIDENCE,
        )
        if (selectedId == null) {
            appendTurn(
                session = session,
                turn = auditTurn(
                    kind = "no_algorithm_match",
                    nodeId = "",
                    userText = userText,
                    extras = mapOf(
                        "llm_intent" to intent.name,
                        "llm_selection_confidence" to selection.confidence,
                    ),
                ),
            )
            return StepResult.NoAlgorithmMatch
        }
        val algorithm = algorithmRepo.findById(selectedId)
            ?: error("algorithm $selectedId selected by LLM not found")
        val entryNode = Walker.entry(algorithm)
        appendTurn(
            session = session,
            turn = auditTurn(
                kind = "pre_pick",
                nodeId = entryNode.id,
                userText = userText,
                extras = mapOf(
                    "llm_intent" to intent.name,
                    "algorithm_id" to selectedId,
                ),
            ),
            currentAlgorithmId = selectedId,
            currentNodeId = entryNode.id,
        )
        return StepResult.NextPrompt(
            nodeId = entryNode.id,
            text = entryNode.text,
            options = entryNode.options.map { PromptOption(it.value, it.label) },
        )
    }

    private suspend fun applyMidWalkStep(session: Session, userText: String): StepResult {
        val algorithmId = requireNotNull(session.currentAlgorithmId) {
            "applyMidWalkStep invoked with null currentAlgorithmId"
        }
        val currentNodeId = requireNotNull(session.currentNodeId) {
            "applyMidWalkStep invoked with null currentNodeId"
        }
        val algorithm = algorithmRepo.findById(algorithmId)
            ?: error("algorithm $algorithmId referenced by session ${session.id} not found")
        val currentNode = algorithm.nodes[currentNodeId] as? QuestionNode
            ?: throw IllegalAlgorithmStateException(currentNodeId)

        val mapping = try {
            llm.mapFreeFormToOption(
                userMessage = userText,
                language = session.language,
                node = currentNode,
            )
        } catch (ce: CancellationException) {
            // Cooperative coroutine cancellation must propagate — never swallow it
            // as an "LLM unavailable" event.
            throw ce
        } catch (e: Exception) {
            // AC-PWVTT4.9 — LLM upstream unavailable; fall back to a button-only
            // re-show of the current node. No retries within a single request.
            // Record the failure so admin tooling can surface a degraded-mode count.
            appendTurn(
                session = session,
                turn = auditTurn(
                    kind = "llm_unavailable",
                    nodeId = currentNodeId,
                    userText = userText,
                    extras = mapOf("error" to e.javaClass.simpleName),
                ),
            )
            return StepResult.ButtonOnlyPrompt(
                nodeId = currentNode.id,
                text = currentNode.text,
                options = currentNode.options.map { PromptOption(it.value, it.label) },
            )
        }

        val pickedOption = try {
            RouterValidator.validateOption(mapping, currentNode)
        } catch (rejected: LlmRejectedException) {
            appendTurn(
                session = session,
                turn = auditTurn(
                    kind = "option_rejected",
                    nodeId = currentNodeId,
                    userText = userText,
                    extras = mapOf("rejection_reason" to rejected.reason.name),
                ),
            )
            return StepResult.RepromptPrompt(
                nodeId = currentNode.id,
                reason = rejected.reason,
                options = currentNode.options.map { PromptOption(it.value, it.label) },
                text = currentNode.text,
            )
        }

        return when (val walkResult = Walker.walk(algorithm, currentNodeId, pickedOption)) {
            is WalkResult.NextQuestion -> {
                appendTurn(
                    session = session,
                    turn = auditTurn(
                        kind = "walk",
                        nodeId = currentNodeId,
                        userText = userText,
                        extras = mapOf(
                            "next_node_id" to walkResult.node.id,
                            "picked_option" to pickedOption,
                        ),
                    ),
                    currentNodeId = walkResult.node.id,
                )
                StepResult.NextPrompt(
                    nodeId = walkResult.node.id,
                    text = walkResult.node.text,
                    options = walkResult.node.options.map { PromptOption(it.value, it.label) },
                )
            }
            is WalkResult.Conclusion -> concludeOrPropose(
                session = session,
                fromNodeId = currentNodeId,
                userText = userText,
                pickedOption = pickedOption,
                node = walkResult.node,
            )
        }
    }

    /**
     * AC-W03362.5 — terminal-node handling. For a `BOOK_APPOINTMENT` conclusion
     * with auto-booking enabled and a specialist code, auto-assign the earliest
     * slot: write a `pending_bookings` row and return [StepResult.ConfirmSlot]
     * (the session stays `active` — the pending row IS the "awaiting confirm"
     * state, so the active-session lookup keeps working). No slot ⇒ conclude with
     * [StepResult.NoSlots]. Every other conclusion (or booking disabled) ⇒ the
     * plain [StepResult.ConclusionPrompt], session concluded — pre-W03362 behavior.
     */
    private suspend fun concludeOrPropose(
        session: Session,
        fromNodeId: String,
        userText: String,
        pickedOption: String,
        node: ConclusionNode,
    ): StepResult {
        val factory = bookingServiceFactory
        val pendingRepo = pendingBookingRepo
        val specialist = node.specialist
        if (node.clinicAction == ClinicAction.BOOK_APPOINTMENT &&
            factory != null && pendingRepo != null && specialist != null
        ) {
            val slot = factory(session.clinicId)
                .findFirstAvailable(session.clinicId, specialist, BOOKING_LOOKAHEAD)
            if (slot != null) {
                pendingRepo.propose(session.clinicId, session.id, slot, specialist)
                appendTurn(
                    session = session,
                    turn = auditTurn(
                        kind = "book_propose",
                        nodeId = fromNodeId,
                        userText = userText,
                        extras = mapOf(
                            "conclusion_node_id" to node.id,
                            "picked_option" to pickedOption,
                            "doctor" to slot.doctorRef.externalId,
                            "slot_at" to slot.start.toString(),
                        ),
                    ),
                    currentNodeId = node.id,
                )
                return StepResult.ConfirmSlot(node, slot.doctorRef.displayName, slot.start, slot.end)
            }
            appendTurn(
                session = session,
                turn = auditTurn("book_no_slots", fromNodeId, userText, mapOf("conclusion_node_id" to node.id)),
                currentNodeId = node.id,
                status = "concluded",
            )
            return StepResult.NoSlots(node)
        }
        appendTurn(
            session = session,
            turn = auditTurn(
                kind = "conclusion",
                nodeId = fromNodeId,
                userText = userText,
                extras = mapOf("conclusion_node_id" to node.id, "picked_option" to pickedOption),
            ),
            currentNodeId = node.id,
            status = "concluded",
        )
        return StepResult.ConclusionPrompt(node = node)
    }

    /** AC-W03362.5 — the session's currently-proposed slot, or null if none is pending. */
    fun pendingBooking(session: Session): PendingBooking? =
        pendingBookingRepo?.find(session.clinicId, session.id)

    /**
     * AC-W03362.6 — patient confirmed the proposed slot. Books it (re-proposing
     * on a lost race), schedules the reminders, clears the pending row, and
     * concludes the session.
     */
    suspend fun confirmPendingBooking(session: Session): StepResult {
        val pendingRepo = pendingBookingRepo ?: return StepResult.BookingContactClinic
        val factory = bookingServiceFactory ?: return StepResult.BookingContactClinic
        val pending = pendingRepo.find(session.clinicId, session.id) ?: return StepResult.BookingContactClinic
        val patient = PatientRef(
            externalId = session.platformUserId, displayName = null, phone = null, languageCode = session.language,
        )
        val outcome = factory(session.clinicId)
            .confirm(session.clinicId, session.id, pending.toSlot(), patient, pending.specialistCode)
        return when (outcome) {
            is BookingOutcome.Confirmed -> {
                pendingRepo.delete(session.clinicId, session.id)
                appendTurn(session, auditTurn("book_confirmed", node(session), null), status = "concluded")
                StepResult.BookingConfirmed(outcome.slot.doctorRef.displayName, outcome.slot.start)
            }
            is BookingOutcome.ReProposed -> {
                pendingRepo.propose(session.clinicId, session.id, outcome.slot, pending.specialistCode)
                appendTurn(session, auditTurn("book_reproposed", node(session), null))
                StepResult.ConfirmSlot(null, outcome.slot.doctorRef.displayName, outcome.slot.start, outcome.slot.end)
            }
            BookingOutcome.ContactClinic, BookingOutcome.NoSlots -> {
                pendingRepo.delete(session.clinicId, session.id)
                appendTurn(session, auditTurn("book_contact_clinic", node(session), null), status = "concluded")
                StepResult.BookingContactClinic
            }
        }
    }

    /**
     * AC-W03362.7 — patient declined the proposed slot. Re-proposes the
     * next-earliest, excluding EVERY slot declined so far this conversation (so a
     * repeated "🔄 Другое время" never cycles back to a rejected time); after
     * [maxDeclines] declines falls back to contact-clinic and concludes.
     */
    suspend fun declinePendingBooking(session: Session): StepResult {
        val pendingRepo = pendingBookingRepo ?: return StepResult.BookingContactClinic
        val factory = bookingServiceFactory ?: return StepResult.BookingContactClinic
        val pending = pendingRepo.find(session.clinicId, session.id) ?: return StepResult.BookingContactClinic
        // Records the just-declined slot and returns the full declined-set + count.
        val declined = pendingRepo.incrementDecline(session.clinicId, session.id)
        val outcome = factory(session.clinicId)
            .decline(
                session.clinicId, session.id,
                declined.declinedSlots.toSet(), pending.specialistCode, declined.declineCount,
            )
        return if (outcome is BookingOutcome.ReProposed) {
            pendingRepo.propose(session.clinicId, session.id, outcome.slot, pending.specialistCode)
            appendTurn(session, auditTurn("book_reproposed", node(session), null))
            StepResult.ConfirmSlot(null, outcome.slot.doctorRef.displayName, outcome.slot.start, outcome.slot.end)
        } else {
            pendingRepo.delete(session.clinicId, session.id)
            appendTurn(session, auditTurn("book_contact_clinic", node(session), null), status = "concluded")
            StepResult.BookingContactClinic
        }
    }

    private fun node(session: Session): String = session.currentNodeId.orEmpty()

    /**
     * Build one history-turn JSON object with the standard shape every
     * `applyStep` path emits: `kind` + `node_id` + (optional)
     * `user_text_encrypted` + `ts` + caller-supplied [extras]. Extras values
     * may be `String`, `Long`, `Int`, `Double`, or `Boolean` — anything that
     * cleanly maps to a `JsonPrimitive`.
     *
     * Centralizing the shape keeps history schema drift in one place and
     * matches the audit-turn contract described in AC-PWVTT4.8.
     */
    private fun auditTurn(
        kind: String,
        nodeId: String,
        userText: String?,
        extras: Map<String, Any> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(kind))
        put("node_id", JsonPrimitive(nodeId))
        if (userText != null) {
            put("user_text_encrypted", JsonPrimitive(encryptForHistory(userText)))
        }
        for ((k, v) in extras) {
            val prim = when (v) {
                is String -> JsonPrimitive(v)
                is Long -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                is Double -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
            put(k, prim)
        }
        put("ts", JsonPrimitive(Instant.now().toString()))
    }

    /**
     * Persist [turn] as one history-array element and (optionally) advance
     * session state in the same atomic UPDATE. Defaulting every state
     * parameter to `null` matches `SessionRepo.updateStateAndAppendTurn`'s
     * "leave column unchanged" semantics for the common "audit-only" turns.
     */
    private fun appendTurn(
        session: Session,
        turn: JsonObject,
        currentAlgorithmId: Long? = null,
        currentNodeId: String? = null,
        status: String? = null,
    ) {
        sessionRepo.updateStateAndAppendTurn(
            sessionId = session.id,
            currentAlgorithmId = currentAlgorithmId,
            currentNodeId = currentNodeId,
            status = status,
            turnJson = turn.toString(),
        )
    }

    private fun encryptForHistory(plaintext: String): String = piiCipher.encrypt(plaintext)

    private fun resolveStartLanguage(
        hint: String?,
        allowed: List<String>,
        default: String,
    ): String =
        if (hint != null && hint in allowed) hint else default

    private companion object {
        /** Lookahead window for the first-available auto-assign (AC-W03362.5). */
        val BOOKING_LOOKAHEAD: Duration = Duration.ofDays(30)

        /**
         * Minimum LLM confidence to launch a triage algorithm from a symptom
         * complaint (AC-PWVTT4.8). Raised above the shared [RouterValidator]
         * default of 0.6 so a marginal, speculative match (e.g. "sleep" → maybe
         * neurology) falls through to [StepResult.NoAlgorithmMatch] rather than
         * launching the wrong flow — a wrong triage is worse than a reprompt.
         */
        const val ALGORITHM_SELECTION_CONFIDENCE = 0.75
    }
}
