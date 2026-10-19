---
title: Telegram bot — patient journey (RU + KK)
milestone: M2
status: archived
archived_at: 2026-06-17T14:33:06Z
id: fr_01KT1YV4DCGVGQCDFN06W3WDWH
created_at: 2026-06-01T16:04:30Z
---

## Requirement

Wire the Telegram bot end-to-end for the patient journey. User opens the clinic's Telegram bot, sees a welcome + goal-pick menu in their language, picks "symptom consultation," walks the symptomizer tree via free-form replies or button picks, receives the conclusion (urgency + specialist + optional tests with prep notes), and proceeds to booking (handed off to FR-4GCYJK for operator-assisted booking in M2). Supports RU + KK from day one via the i18n catalog (requirements § 2.2).

This FR implements the M1 scaffold's `TelegramAdapter` body and wires the bot handler graph in `messaging/src/main/kotlin/com/symptree/messaging/core/`. It also ships the `MessageCatalog` (i18n surface) since Telegram is its sole M2 consumer.

References: requirements.md § 2.2 (i18n); technical-spec.md § 3.2 (`bot/ingress` contract), § 4.3 (i18n catalog).

## Acceptance Criteria

- AC-W3WDWH.1: `TelegramAdapter` (in `messaging/src/main/kotlin/com/symptree/messaging/adapter/`) implements every `MessagingPort` method against the kotlin-telegram-bot library (currently `TODO()` from M1 scaffold). `sendText`, `sendPhoto`, `sendMenu`, `removeButtons`, `updateButtons`, `sendTypingIndicator` all functional.
- AC-W3WDWH.2: The bot dispatch loop in `MessagingMain` polls Telegram (`long polling`; webhook deferred to deployment milestone), normalizes incoming updates into `IncomingMessage`, and posts each to `POST /api/v1/clinics/{slug}/bot/ingress` (clinic slug carried in bot config — one bot per clinic for M2). The backend response (a list of `OutgoingAction`s) is replayed via `TelegramAdapter`.
- AC-W3WDWH.3: User language is detected from the Telegram `Update.message.from.language_code` field. If the code is in the clinic's `allowed_languages`, it's used; otherwise the clinic's `default_language` is used. The user can change language mid-session via a `/language` command or via a menu option.
- AC-W3WDWH.4: The bot welcome flow: when an unknown user starts the bot, send a welcome message + a menu of three goals (in the user's language): `symptom_consultation`, `appointment_booking`, `service_info`. The menu uses inline-keyboard buttons. Selection routes to the corresponding flow.
- AC-W3WDWH.5: `symptom_consultation` flow: bot asks the user to describe their main complaint. The user replies (free-form text). Backend's `SessionService.applyStep` selects the algorithm and starts the walker (via FR-PWVTT4). The bot renders the next question + inline-keyboard options (in user's language).
- AC-W3WDWH.6: At each walker step, the user can reply with free-form text OR pick an option from the inline keyboard. Free-form replies go through `OpenAiLlmRouterClient.mapFreeFormToOption` (FR-PWVTT4); on LLM rejection the bot re-prompts with a clarifying message + the same keyboard.
- AC-W3WDWH.7: On reaching a `ConclusionNode`, the bot renders the conclusion: urgency-icon-prefixed diagnosis text, action recommendation, specialist name (if any), test list with prep notes, and either a "book appointment" CTA (when `clinic_action == BOOK_APPOINTMENT`) or an "emergency action" message (when `clinic_action == BYPASS_APPOINTMENT`). Booking CTA hands off to FR-4GCYJK (operator-assisted booking) for M2; M3 FR-2VQ7CA switches the hand-off target to automated booking when the clinic opts in via `clinics.config.booking.automated_enabled`.
- AC-W3WDWH.8: `MessageCatalog` exists in `core/src/main/kotlin/com/symptree/core/i18n/` with `get(key, lang, params?)` API. Resource bundles live in `src/main/resources/i18n/<lang>.yaml`. Catalog is loaded once at `CoreApp.main` startup and is immutable after.
- AC-W3WDWH.9: M2 ships YAML catalogs for `ru` and `kk` with **every** string key that any handler or route uses. A build-time test scans every `*.kt` file in `routes/`, `core/`, `adapter/`, `client/`, `booking/`, `scheduler/` directories and asserts no user-facing string literal in Russian Cyrillic or Kazakh Cyrillic (regex sweep for non-test files). All user-facing strings must come from `MessageCatalog.get(...)`.
- AC-W3WDWH.10: An end-to-end integration test plays a recorded sequence of Telegram updates through the bot module against a Testcontainers-Postgres backend (FakeLlmRouterClient; the conclusion-reaching path needs no booking provider) and asserts the conclusion-reaching path for at least one algorithm in both RU and KK.

## Technical Design

Builds on technical-spec.md § 3.2 + § 4.3 + the M1 scaffold's `messaging/port/MessagingPort`. Specifically:

- **One bot per clinic (M2 simplifier):** each clinic registers its own Telegram bot token via the secret store. `MessagingMain` reads the token from env / secret store, starts one poller per clinic. Multi-clinic-per-bot (bot-as-platform with clinic routing via deep-link payload) deferred — adds significant complexity around `/start` payload parsing.
- **Dispatch shape:**
  ```kotlin
  // messaging
  fun main() {
      val config = MessagingConfig.fromEnv()  // includes clinic slug + backend URL
      val backendClient = CoreClient(config.backendUrl, apiKey = config.botApiKey)
      val telegramAdapter = TelegramAdapter(config.telegramBotToken)
      val handler = IngressHandler(backendClient, telegramAdapter, config.clinicSlug)
      telegramAdapter.startPolling { update ->
          val incoming = normalize(update)
          val outgoing = backendClient.botIngress(config.clinicSlug, incoming)
          outgoing.forEach { telegramAdapter.apply(it, incoming.user) }
      }
  }
  ```
- **`OutgoingAction` sealed type** (backend → bot):
  ```kotlin
  sealed interface OutgoingAction {
      data class SendText(val text: String, val disableLinkPreview: Boolean = false) : OutgoingAction
      data class SendMenu(val text: String, val options: List<MenuOption>, val layout: MenuLayout) : OutgoingAction
      data class SendPhoto(val photoBytes: ByteArray, val mimeType: String, val caption: String?, val options: List<MenuOption>) : OutgoingAction
      data class SendTyping(val unit: Unit = Unit) : OutgoingAction
      data class RemoveButtons(val messageId: String) : OutgoingAction
      data class UpdateButtons(val messageId: String, val options: List<MenuOption>, val layout: MenuLayout) : OutgoingAction
  }
  ```
- **YAML parser dep:** `com.charleskorn.kaml:kaml` — kotlinx-serialization-based YAML, matches the project's existing `@Serializable`-first pattern (CLAUDE.md "prefer @Serializable over Jackson / Gson"). Added to `core/build.gradle.kts` as part of this FR. Catalog values are flat `Map<String, String>`; no nested types needed.
- **i18n catalog YAML shape:**
  ```yaml
  # i18n/strings/ru.yaml
  bot.welcome.title: "Здравствуйте! Я помогу разобраться с симптомами и записаться к врачу."
  bot.welcome.goal_symptom: "🩺 Разобраться с симптомами"
  bot.welcome.goal_booking: "📅 Записаться к врачу"
  bot.welcome.goal_info: "ℹ️ Узнать об услугах"
  bot.symptom.prompt_main_complaint: "Опишите, что вас беспокоит."
  bot.reprompt.unclear_answer: "Я не уверен, что понял. Выберите один из вариантов или ответьте подробнее."
  bot.conclusion.urgency.emergency: "🚨 Экстренная ситуация"
  bot.conclusion.urgency.urgent: "⚠️ Срочно"
  bot.conclusion.urgency.routine: "📌 В плановом порядке"
  bot.conclusion.urgency.self_care: "🏠 Самопомощь"
  bot.conclusion.specialist_label: "Рекомендуемый специалист: {specialist}"
  bot.conclusion.tests_label: "Предварительные исследования:"
  bot.conclusion.book_cta: "Записаться сейчас"
  bot.error.llm_unavailable: "Сейчас отвечаю в упрощённом режиме — выберите вариант из списка."
  llm.system_intent_classifier: "..."  # used by FR-PWVTT4
  llm.system_option_mapper: "..."
  ```
  KK catalog has the same keys, translated.
- **Language detection priority:** explicit session override > Telegram `language_code` > clinic `default_language`. Fall through to `default_language` if any preceding source produces a code outside `allowed_languages`.

## Testing

Per testing-spec.md. Specifically:

- Unit tests for `TelegramAdapter` — each method, error-translation paths.
- Unit tests for `IngressHandler` (bot module) — happy + backend-error fallback.
- Unit tests for `MessageCatalog` — present-key lookup, missing-key fallback (logs WARN + returns the key), parameter interpolation.
- The literal-sweep test per AC-W3WDWH.9 lives in `src/test/kotlin/com/symptree/core/i18n/NoHardcodedLiteralsTest.kt` and uses Kotlin source-file regex to fail the build on any Russian/Kazakh Cyrillic in non-test code outside `i18n/strings/`.
- End-to-end test per AC-W3WDWH.10 lives in `src/test/kotlin/com/symptree/core/integration/TelegramJourneyIT.kt`. Replays Telegram update fixtures (`src/test/resources/telegram/`) through `POST /bot/ingress` against a Testcontainers Postgres + seeded clinic + seeded algorithm + FakeLlmRouterClient. Asserts the full Scenario 1 path reaches a `ConclusionNode` in RU and again in KK.

## Notes

- WhatsApp adapter stays as the M1 scaffold's `TODO()` body. Its port shape locks in here (no schema change to `MessagingPort`); M3 wires the Meta Cloud API implementation.
- Webhook (vs long-polling) mode is a deployment concern, not an FR. Both work with the same handler graph.
- Per-clinic bot tokens require a registration step in the clinic-admin flow (`PUT /api/v1/clinics/{slug}/config` with `telegram_bot_token_ref`). Token rotation is via the same endpoint.
- Inline-keyboard layout heuristic: < 4 options → vertical 1-column; 4-8 options → 2-column grid; > 8 options → vertical scroll. Pre-determined by `MenuLayout` hint from the conclusion's option count.
- Photo flows (sending an image of test-prep instructions, e.g.) deferred until algorithms ship with image references — none of the M2 ported trees use images.
