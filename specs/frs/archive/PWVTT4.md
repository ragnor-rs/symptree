---
title: Symptomizer session + LLM router
milestone: M2
status: archived
archived_at: 2026-06-17T14:33:06Z
id: fr_01KT1YV4DC575R8S2N0BPWVTT4
created_at: 2026-06-01T16:04:30Z
---

## Requirement

Glue layer between the bot-facing surface (FR-W3WDWH) and the deterministic walker (FR-KT4697). Owns: session storage (one row per active session, JSONB history), language detection + override, intent classification, free-form-reply → walker-option mapping, LLM-output validation, button-only fallback when the LLM is unavailable.

This is the most safety-critical FR in M2. Per requirements.md § 2.3 the LLM has two and only two jobs: classify the user's intent into `{SYMPTOM, BOOKING, FAQ, FALLBACK}`, and translate a free-form yes-style reply into one of the current node's option_values. Every LLM output is validated against the walker's view of legal next-state before being applied. A hallucinated option_value is rejected and the user is re-prompted.

References: requirements.md § 2.3 (LLM auxiliary contract); technical-spec.md § 2.1 (`sessions` schema), § 3.5 (`LlmRouterClient` port), § 4.1 (walker pure-function contract).

## Acceptance Criteria

- AC-PWVTT4.1: `sessions` table exists per technical-spec.md § 2.1; migration `database/03-migrate-sessions.sql` applies cleanly. UNIQUE `(platform, platform_user_id, clinic_id, status)` enforces "one active session per user per clinic per platform".
- AC-PWVTT4.2: `SessionService.startOrResume(clinicId, platform, platformUserId, languageHint?)` returns the existing active session for the tuple, or creates a new one. `languageHint` is the platform-supplied language code (e.g., Telegram `language_code`); if absent it falls back to the clinic's `default_language`.
- AC-PWVTT4.3: `SessionService.setLanguage(sessionId, language)` updates the active session's `language` column and appends an audit turn to `history`. The new language must be in the clinic's `allowed_languages` set; otherwise returns a typed `LanguageNotAllowedException`.
- AC-PWVTT4.4: `LlmRouterClient` port exists per technical-spec.md § 3.5 with the two methods (`classifyIntent`, `mapFreeFormToOption`).
- AC-PWVTT4.5: `OpenAiLlmRouterClient` is the M2 default impl; uses OpenAI Chat Completions with structured outputs (JSON schema) so the model literally cannot return an `intent` outside the enum or an `option_value` outside the node's option set. The OpenAI API key is read from env `OPENAI_API_KEY`; missing key fails fast at `CoreApp.main` startup.
- AC-PWVTT4.6: `RouterValidator.validateIntent(classification: IntentClassification, allowed: Set<Intent>)` returns the intent when valid + above confidence threshold (0.6 default, configurable per clinic); throws `LlmRejectedException` otherwise.
- AC-PWVTT4.7: `RouterValidator.validateOption(mapping: OptionMapping, node: QuestionNode)` returns the option_value when it appears in `node.options.map { it.value }` AND `mapping.confidence >= 0.6`; throws `LlmRejectedException` otherwise. The exception carries a structured reason (`LlmRejectedException.Reason` enum: `OUT_OF_SET`, `LOW_CONFIDENCE`, `NULL_PICK`, `NON_SYMPTOM_INTENT`) for re-prompt rendering. `NON_SYMPTOM_INTENT` is surfaced by `SessionService.applyPrePickStep` when the LLM correctly classifies a non-symptomizer intent (BOOKING / FAQ / FALLBACK) — the routing-signal case, distinct from a hallucinated option pick — so the bot can render a different UX than a reprompt.
- AC-PWVTT4.8: `SessionService.applyStep(session, userText)` orchestrates one walker step: (a) if session has no current_algorithm, run intent classification + algorithm selection from the clinic's enabled algorithms; (b) if session is mid-walk, run free-form-to-option mapping; (c) validate the LLM output; (d) on validation pass, call the walker and update session state; (e) on validation fail, return a `ReprompPrompt` payload for the bot to render. The session row is updated atomically (single UPDATE for state + history append).
- AC-PWVTT4.9: When `OpenAiLlmRouterClient` throws (network error, 5xx, timeout), `SessionService.applyStep` falls back to button-only mode — returns a `ButtonOnlyPrompt` payload listing the current node's options in the user's language so the bot can render an inline keyboard. The LLM call is not retried within the request (timeout is fast, user shouldn't wait).
- AC-PWVTT4.10: PII in `sessions.history` is encrypted at the application layer (per requirements § 2.5). `user_text_encrypted` is AES-256-GCM with key from env `SYMPTREE_PII_KEY`. Decryption happens only in the retention/anonymization job and in an admin "export my data" endpoint (M3+).

## Technical Design

Builds on technical-spec.md § 2.1 + § 3.5 + § 4.1 + § 4.4. Specifically:

- **Module layout:** `core/src/main/kotlin/com/symptree/core/{symptomizer/SessionService.kt, llm/{LlmRouterClient,OpenAiLlmRouterClient,RouterValidator,Intent}.kt, db/SessionRepo.kt}`.
- **OpenAI client choice:** `com.aallam.openai:openai-client` (Kotlin-native, kotlinx-serialization, multiplatform-ready). Alternative: official `com.openai:openai-java` (Java-first SDK). Pick at implementation time based on which has better structured-outputs ergonomics in 2026 — both are acceptable; lock the choice in the build dependencies.
- **Structured-output prompts:** the OpenAI call passes a JSON schema for the response that constrains `intent` to the four-value enum and `picked_option_value` to the node's option set (computed per-call via `Walker.availableOptionValues`). The model literally cannot return out-of-set values — but `RouterValidator` checks anyway (defense in depth against API quirks + model-version changes).
- **Prompt construction:** prompts are templated in `i18n/strings/<lang>.yaml` as `llm.system_intent_classifier`, `llm.system_option_mapper`. User-message body is interpolated verbatim. No multi-turn conversation context sent to OpenAI; each call is stateless and carries only the immediately relevant context (current language, current node, user message). Session history lives in Postgres, not in LLM context, for M2.
- **Confidence threshold:** two intentionally-split floors. Intent classification and free-form option mapping use the `RouterValidator` default of **0.6** (below = LLM rejection). **Algorithm selection** — picking which enabled triage flow a symptom complaint launches — uses a higher **0.75** floor (`SessionService.ALGORITHM_SELECTION_CONFIDENCE`), so a marginal/speculative match (e.g. "sleep" → maybe neurology) falls through to `StepResult.NoAlgorithmMatch` (a reprompt) rather than launching the wrong flow; a wrong triage is worse than asking the patient to restate. Per-clinic override (`clinics.config.llm.confidence_threshold`) is **deferred to M3** — both floors are currently constants; real-world tuning happens with production traffic.
- **Re-prompt UX:** on validation rejection, the bot renders a clarifying message ("Could you say that more directly? You can also pick from these options:") + inline-keyboard with the option labels. Two consecutive rejections triggers full button-only mode for the remainder of the session.

## Testing

Per testing-spec.md. Specifically:

- Unit tests for `RouterValidator` — happy + every rejection branch (intent not in allowed set, intent below confidence, option not in node, option below confidence).
- Unit tests for `SessionService.applyStep` using `FakeLlmRouterClient` (testing-spec.md § 5.5) — every state transition (pre-pick / mid-walk / conclusion), every fallback (LLM unavailable, low confidence, hallucination).
- Integration tests for `SessionRepo` — Testcontainers, UNIQUE constraint enforcement, history JSONB round-trip with encrypted text.
- LLM-unavailable test: `FakeLlmRouterClient` throws — assert `ButtonOnlyPrompt` is returned, no exception escapes.
- Cross-tenant test: a session for `clinic_a` cannot be loaded or mutated through a `clinic_b`-scoped call.
- Encryption test: `user_text_encrypted` round-trips through AES-256-GCM with a fixed test key; ciphertext is different across runs (IV randomization).

## Notes

- The 0.6 confidence threshold is a starting value. Real-world tuning happens in M3+ with production traffic.
- "Two consecutive rejections → button-only for rest of session" is a heuristic. Re-evaluate after pilot.
- OpenAI data-residency risk for KZ market is logged in technical-spec.md § 6 and is a known M3 decision point.
- Anonymization job + admin export endpoint are deferred to M3 (covered by FR for retention/admin).
- LLM cost monitoring + per-clinic quotas deferred to M3+.
