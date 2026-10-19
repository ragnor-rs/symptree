---
title: Editable clinic service-info shown in the bot
milestone: M3
status: active
archived_at: null
id: fr_01KV8BRQVF2R48B4MD3Z6AXT7Z
created_at: 2026-06-16T14:01:17Z
---

## Requirement

Let a clinic admin write a free-text **"About / services"** blurb in the admin panel and have the bot show it to patients who tap **"ℹ️ Узнать об услугах"** — replacing the current "coming soon" stub (`service_info` → `GoalPromptKind.SERVICE_INFO_COMING_SOON` → `bot.faq.coming_soon`).

The content is a **per-language free-text blob** (`ru` + `kk`), so the bot can show it in the patient's language. It is stored in `clinic.config.preferences.service_info = { ru, kk }`, edited on the **clinic Settings page** (extending FR-FXJEVE's GET/PUT), and rendered by the bot's `service_info` goal. An empty/missing blurb for the patient's language falls back to the existing "уточните у администратора" message.

References: FR-FXJEVE (the clinic Settings page + `GET`/`PUT /api/v1/admin/clinic-settings` this extends — **dependency: FXJEVE must land first**, PR #11), FR-W3WDWH (the welcome goal menu with `service_info`), FR-VWRNDR (semantic DTOs; bot renders text core supplies, owns its own chrome), brainstorm 2026-06-16 (per-language free-text blob; reuse the existing empty-fallback message).

## Acceptance Criteria

- AC-6AXT7Z.1: `GET /api/v1/admin/clinic-settings` is extended to return `service_info` as a per-language object `{ "ru": <string>, "kk": <string> }` (empty strings when unset), read from `config.preferences.service_info`. Semantic only (no chrome) per FR-VWRNDR; reuses the FR-FXJEVE settings DTO.
- AC-6AXT7Z.2: `PUT /api/v1/admin/clinic-settings` accepts `service_info` in the full-object body and persists it into `config.preferences.service_info`, merged atomically with the other preference keys via the FR-FXJEVE single-statement write (no other config key is lost). Admin-only (operator 403), `clinic_id` from token only.
- AC-6AXT7Z.3: Validation (422, no write): each language value is a string within a max length (e.g. ≤ 2000 chars — within one Telegram message); keys outside the platform language set `{ru, kk}` are rejected. Both languages empty is allowed (clears the blurb).
- AC-6AXT7Z.4: The admin Settings page (FR-FXJEVE) gains a `service_info` editor — one multi-line text field per language (RU + KK), shown editable for admins and read-only for operators, saved via the existing single Save. Localized labels via the SPA i18n bundle; no hardcoded Cyrillic.
- AC-6AXT7Z.5: Tapping the `service_info` welcome goal no longer returns `SERVICE_INFO_COMING_SOON`. When the clinic has non-empty `service_info` for the patient's session language, core returns a new semantic outcome carrying that text, and `messaging` renders it as a plain text message (the blurb is clinic-authored content, rendered verbatim — FR-VWRNDR).
- AC-6AXT7Z.6: When `service_info` for the patient's language is empty/missing, the bot falls back to the existing `bot.faq.coming_soon` message (no new "empty" string) — so an un-configured clinic behaves exactly as today.
- AC-6AXT7Z.7: Language selection follows the patient's session language; a clinic that filled only `ru` and a `kk` patient gets the fallback (AC.6), not the `ru` text — the blurb is never shown in the wrong language.

## Technical Design

Builds on FR-FXJEVE (Settings page + clinic-settings GET/PUT), FR-W3WDWH (goal menu), FR-VWRNDR (semantic DTOs).

**Backend (`core`)** — extends FR-FXJEVE:
- `ClinicSettingsResponse` + `ClinicSettingsUpdateRequest` gain `service_info: Map<String, String>` (`@SerialName("service_info")`). GET reads `config.preferences.service_info`; the FR-FXJEVE `mergeSettingsIntoConfig` writes it under `preferences.service_info` alongside timezone/min_lead/first_day (same atomic `updateSettings`).
- `validateClinicSettings` gains: every key ∈ `{ru, kk}`, every value length ≤ `SERVICE_INFO_MAX` (e.g. 2000). Empty map / empty strings allowed.
- **Bot goal**: `BotIngressRoutes.handleGoalSelection("service_info")` reads `clinic.config.preferences.service_info[sessionLang]`; non-blank ⇒ new `IngressOutcomeDto.ServiceInfo(text)`; blank/absent ⇒ keep `GoalPromptKind.SERVICE_INFO_COMING_SOON`. `service_info` is clinic-authored free text localized by data (the clinic typed each language), so core passes the already-correct-language string through — consistent with VWRNDR (algorithm/clinic-authored text crosses the wire; only chrome stays in messaging).

**Messaging** — new port outcome `IngressOutcome.ServiceInfo(text)`; `IngressRenderer` renders it as a single `SendText(text)`. No new catalog key for the populated path (the text is clinic content); the empty path still uses `bot.faq.coming_soon`.

**Frontend (`admin-panel`)** — extends FR-FXJEVE's Settings page:
- `ClinicSettings` / `ClinicSettingsUpdate` types gain `service_info: { ru: string; kk: string }`.
- `SettingsForm` gains a `<textarea>` per language (RU, KK) bound into the saved object; read-only for operators.
- i18n: `settings.service_info`, per-language sub-labels, a char-count/limit hint, and the 422 error mapping (`invalid_service_info`). RU + KK parity.

**Dependency / sequencing:** this FR edits the exact files FR-FXJEVE introduces (`AdminRoutes` settings DTOs/validation, `ClinicRepo` is unchanged, `SettingsForm`, settings i18n). Implement **after FXJEVE merges** (PR #11) to avoid a stacked/ conflicting branch; the M3 plan notes the ordering.

## Testing

Framework: JUnit 5 + `ktor-server-test-host` (core); Vitest + Testing Library (admin-panel).

**Backend**
- `ClinicSettingsRoutesTest` (extends FR-FXJEVE's): GET returns `service_info` (empty default + configured value); admin PUT round-trips `service_info` merged into `config.preferences` while preserving the other settings keys (AC.1/.2); operator PUT 403 (reuses FXJEVE gate); 422 on over-length / non-`{ru,kk}` key (AC.3).
- `BotIngressRoutes` test: `goal:service_info` with non-empty `service_info[lang]` returns the `ServiceInfo` outcome carrying that exact text (AC.5); empty/missing → the existing coming-soon outcome (AC.6); a `kk` patient on a `ru`-only clinic gets the fallback, never the `ru` text (AC.7).

**Frontend**
- `SettingsForm` renders an editable per-language service-info textarea for admins, read-only for operators (AC.4); saving includes `service_info` in the PUT body (no `clinic_id`).
- i18n parity covers the new keys (RU + KK), no hardcoded Cyrillic.

## Notes

- **Hard dependency on FR-FXJEVE** (the Settings page + clinic-settings endpoint). Build order: FXJEVE (PR #11) → this FR. If FXJEVE is still open at implement time, branch from its branch or rebase after merge.
- Plain text only (no rich formatting/markdown) for the pilot — Telegram/WhatsApp formatting differences make rich text a later concern.
- Per-language by design (brainstorm): the blurb is shown only in the patient's language; partially-filled clinics fall back rather than cross-show languages (AC.7).
- Structured fields (services list / hours / address / phone) were considered and deferred — a single free-text blob is the pilot MVP.
