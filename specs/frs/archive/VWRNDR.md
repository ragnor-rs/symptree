---
title: "View-layer i18n: backend emits semantic DTOs, frontend owns all localization (ingress + reminders)"
milestone: M2
status: archived
archived_at: 2026-06-17T14:33:06Z
id: fr_01KTW0A9B2C3D4E5F6G7VWRNDR
created_at: 2026-06-11T15:00:00Z
---

## Requirement

**The wire is a backend↔frontend contract carrying *semantic data*, never *view
artifacts*.** `core` is the backend; `messaging` is the frontend. Everything that
pertains to the view — localization strings **and their keys**, datetime
formatting, urgency icons, layout, chrome templates — belongs to the frontend.

`core` must **never** emit a localization key, a catalog key, a finished chrome
string, or a view format across the wire. It emits **filled DTOs with concrete,
typed, data-holding fields**; the frontend inspects those fields and decides which
of *its own* strings to render. A localization key (`bot.symptom.no_algorithm_match`)
is itself a view artifact — a handle into a catalog only the frontend owns — so
putting a key on the wire is the same layering violation as putting the finished
string on the wire, one indirection up.

Today the inverse is true on **two** surfaces:

1. **Ingress** (`/bot/ingress`): `core` renders all user-facing chrome through an
   in-`core` `MessageCatalog` (concentrated in `routes/BotIngressRoutes.kt`) and
   returns **finished strings** in `OutgoingActionDto`. `messaging` is a dumb replayer.
2. **Reminders/feedback** (`/bot-internal/outgoing`, FR-W03362): `core` builds a
   `{platform_user_id, language, catalog_key, params}` payload and POSTs it to
   `messaging`, which renders the `catalog_key` in its own `OutgoingMessageCatalog`.
   The `catalog_key` is also **persisted in `reminders.payload`** (DB). This was
   W03362's interim "Variant A" — it moved *rendering* to the frontend but left the
   *key* (a view artifact) on the wire and in the backend's database.

**This FR corrects both.** It **supersedes** W03362's Variant A rather than
generalizing it. After this FR:

- The three buckets of the original sketch collapse to **two**: (1) **domain /
  algorithm-tree text** — `core`-owned multilingual data, localized **by `core`**
  to the session language and carried as **concrete string fields**; (2)
  **everything else** (chrome, outcomes, urgency, booking state, reminder kind) —
  carried as **semantic typed fields** (enums, sealed-variant discriminators,
  concrete data values) that the frontend maps to its own strings.
- LLM-generated free-text (`OptionMapping.clarifyingQuestion`) is **not** surfaced
  to the user (product decision — see AC-VWRNDR.4); `core` emits only the semantic
  `rejectReason`. LLM-passthrough is deferred to a future FR.
- The catalog (keys + bundles) lives **100% in `messaging`**, on **both** paths,
  as one disjoint set. `core` holds zero presentation strings and zero keys.

`core` keeps exactly what is its own: the **session language** (resolved from the
bot's per-turn language hint, persisted, gated by the clinic's `allowed_languages`)
and the **localization of algorithm-tree text** to that language. `core` owns
algorithm texts **only**.

References: FR-W03362 (the path this FR re-architects); technical-spec.md § 3.2
(ingress contract — becomes a semantic view model), § 4.3 (i18n catalog —
relocates to `messaging`), § 4.5 (reminder scheduler — Variant A's `catalog_key`
shape is replaced by a semantic reminder DTO); `routes/BotIngressRoutes.kt` (the
core chrome site); design discussion 2026-06-12 (backend-emits-semantic-DTOs rule;
scope B "total fix"; tree-text localized in core; Option C for LLM free-text).

## Acceptance Criteria

- AC-VWRNDR.1: The `MessageCatalog` implementation and every `i18n/<lang>.yaml`
  bundle live **only** in the **`messaging`** module. `core` contains no
  `MessageCatalog` class, no `i18n/*.yaml` resources, and **zero `catalog.get` call
  sites** (today: `routes/BotIngressRoutes.kt` for ingress). A guard test asserts
  `core` has no presentation-string lookups and no localization-key constants.
- AC-VWRNDR.2: The `/bot/ingress` response is a list of **semantic outcome DTOs** —
  typed variants (`Welcome`, `LanguagePicker`, `GoalPrompt`, `NextQuestion`,
  `Reprompt`, `ButtonOnly`, `Conclusion`, `ConfirmSlot`, `NoSlots`,
  `BookingConfirmed`, `BookingContactClinic`, `NoAlgorithmMatch`,
  `LanguageChanged`, `LanguageNotAllowed`) — each carrying **concrete, typed fields
  only**. No localization key, no catalog key, no finished chrome string, and no
  Cyrillic/Kazakh chrome literal crosses the wire from `core`. `messaging` maps each
  variant to its own catalog keys, renders, and assembles the final platform message.
- AC-VWRNDR.3: **Algorithm-tree text** (question text, diagnosis, action, test
  names, option labels) is localized **by `core`** to the session language —
  `core` keeps the `localize()` + `allowed_languages` fallback — and carried in the
  outcome DTO as **concrete string fields**. `messaging` displays them verbatim and
  **never** localizes domain text and never looks tree text up in its catalog.
- AC-VWRNDR.4: **LLM-generated free-text** (`OptionMapping.clarifyingQuestion`) is
  **not surfaced** to the user (product decision 2026-06-12: keep the algorithm path
  deterministic; do not show unvalidated LLM free-text). On an option-mapping
  rejection `core` emits the semantic `rejectReason` field only; `messaging` renders
  its **own static reprompt** for that reason. This is a pure relocation of today's
  static-reprompt behaviour to the frontend — **no user-visible behaviour change**.
  `clarifyingQuestion` remains parsed-then-discarded; LLM-passthrough is deferred to
  a future FR.
- AC-VWRNDR.5: The **reminder/feedback/outgoing path** emits a **semantic DTO**
  (e.g. `ReminderDue { kind, doctorName, slotAt, clinicZone }`, and the feedback
  analog) over `POST /bot-internal/outgoing`. `catalog_key` is removed from **both**
  the `/bot-internal/outgoing` wire contract **and** from `reminders.payload`
  (schema alter; no data migration — M2 is pre-release). `messaging` maps the
  semantic `kind` to its own catalog key and renders.
- AC-VWRNDR.6: The catalog exists in **exactly one place** — `messaging`. The
  ingress chrome keys and the reminder/feedback/booking keys (`bot.reminder.*`,
  `bot.feedback.*`, `bot.booking.*` from FR-W03362) form **one disjoint set** with
  **no duplication**; `messaging`'s existing `OutgoingMessageCatalog` is unified into
  the single catalog (or renamed to it).
- AC-VWRNDR.7: **View formatting is frontend-only.** `core` sends `slotAt: Instant`
  + `clinicZone: String` (concrete data); `messaging` formats the `dd.MM HH:mm`
  wall-clock string and selects the urgency icon from the `Urgency` enum. `core`
  emits **no format pattern, no icon, no chrome label** (e.g. the "Анализы:" /
  tests-list header is a `messaging` string keyed off the structured test list).
- AC-VWRNDR.8: Coverage moves with the catalog. (a) A ru↔kk **bundle-parity** test
  lives in `messaging` and spans the **full** unified catalog. (b) The chrome-literal
  sweep asserts `core` emits **no chrome string literals and no localization-key
  constants** on either path. (c) A `messaging` **key-coverage** test asserts every
  semantic outcome variant (and every `ReminderDue.kind`) maps to a catalog key
  backed in every language bundle.

## Technical Design

**Boundary, one line:** `core` emits *what happened* as typed data; `messaging`
decides *how to say it*. The wire is `core`'s semantic outcome model, not a string.

### Ingress path (`/bot/ingress`)

- **Semantic outcome DTOs replace `OutgoingActionDto`-of-finished-strings** as the
  response body. The new DTO set is essentially `core`'s existing `StepResult`
  sealed type (plus the chrome-only outcomes that `BotIngressRoutes` builds inline
  today — `Welcome`, `LanguagePicker(allowed)`, `GoalPrompt`/`SymptomPrompt`,
  `LanguageChanged(languageName)`, `LanguageNotAllowed(requested)`) made
  `@Serializable` and shipped over the wire. Each variant carries concrete fields:
  - tree text → concrete session-language strings (`core` calls `localize()`),
  - discriminators → typed enums (`Urgency`, `ClinicAction`, `RejectReason`),
  - data → `doctorName: String`, `slotAt: Instant`, `slotEnd: Instant`,
    `clinicZone: String`, option `{value, label}` pairs (label already localized).
- **`Conclusion` is the structured hard case.** Today `conclusionBodyText`
  interleaves chrome (urgency icon, "Анализы:" label, bullet layout) with domain
  text (diagnosis, action, test names, prep notes) into one string. The DTO carries
  the pieces — `Conclusion { urgency: Urgency, diagnosis: String, action: String,
  tests: List<TestItem{ name: String, prep: String? }>, clinicAction: ClinicAction }`
  — and `messaging` assembles the icon + label + layout from its catalog.
- **`core` side:** delete `i18n/MessageCatalog.kt` + `src/main/resources/i18n/*.yaml`;
  rewrite `routes/BotIngressRoutes.kt` to construct semantic DTOs (drop the `catalog`
  parameter entirely). Keep `localize()` and the `allowed_languages`/session-language
  logic in `core` — it operates on `core`-owned domain data. The response still
  carries the resolved session `lang` so `messaging` renders its chrome consistently
  with the tree text `core` localized.
- **`messaging` side:** the unified catalog gains the ingress chrome keys; a new
  outcome→`OutgoingAction` renderer in `core/IngressHandler` (or a dedicated
  renderer) does the chrome assembly: reprompt-by-`rejectReason` mapping, urgency
  icon, tests-block label + bullets, datetime formatting, menu layout, then replays
  via `MessagingPort`.

### Reminder / feedback / outgoing path (`/bot-internal/outgoing`)

- **Wire contract change:** `{platform_user_id, language, catalog_key, params}` →
  `{platform_user_id, language, <semantic DTO>}` where the DTO is e.g.
  `ReminderDue { kind: ReminderKind, doctorName: String, slotAt: Instant,
  clinicZone: String }` (and the feedback-prompt analog). `messaging` maps
  `kind` → its catalog key.
- **Schema change:** `reminders.payload` stops storing `catalog_key`; it stores the
  semantic fields needed to rebuild the DTO at dispatch (or the scheduler joins
  `reminders` → `bookings`/`sessions` to assemble it). Alter `08-migrate-reminders.sql`
  (or add a follow-on migration) — **no data migration**, M2 is pre-release.
- **`ReminderScheduler`** builds the semantic DTO instead of a `catalog_key` payload;
  `FeedbackHandler` likewise. `BOT_INTERNAL_SHARED_SECRET` auth unchanged.

### Cross-cutting spec updates (rewritten by `/implement`, not now)

- technical-spec.md **§ 3.2** (ingress contract) → semantic view-model description.
- technical-spec.md **§ 4.3** (i18n catalog) → catalog relocates to `messaging`,
  single source, both paths.
- technical-spec.md **§ 4.5** (reminder scheduler) → replace the "core does not
  render but emits `catalog_key`" line with the semantic-reminder-DTO contract.

### Out of scope

- Surfacing LLM free-text to users (AC-VWRNDR.4 keeps it discarded) — future FR.
- Any change to the symptomizer walk, LLM router, validation, or booking logic —
  this is a presentation-boundary refactor; domain behaviour is unchanged.

## Testing

Per testing-spec.md.

- `MessageCatalogTest` (relocated to `messaging`, unified) — key lookup + `{param}`
  interpolation + missing-key fallback over the full catalog.
- `IngressViewModelContractTest` — `/bot/ingress` returns semantic outcome DTOs
  carrying concrete fields, never a localization key / catalog key / finished chrome
  string; a representative `Conclusion` (urgency + diagnosis/action/tests passthrough)
  and a `ConfirmSlot` round-trip, and `messaging` reassembles the expected
  user-visible message (icon, label, formatted datetime) byte-for-byte against the
  pre-refactor golden.
- `OutgoingContractTest` — `/bot-internal/outgoing` accepts the semantic
  `ReminderDue`/feedback DTO and rejects a payload carrying `catalog_key`; the
  `reminders.payload` row holds no `catalog_key`.
- `BundleParityTest` (messaging) — ru↔kk key parity for the **full** unified catalog.
- `CoreHasNoChromeTest` (guard) — zero `catalog.get`, no `i18n/*.yaml`, and no
  localization-key string constants anywhere under `core` (sweeps `routes/`,
  `symptomizer/`, `scheduler/`, `booking/`).
- `OutcomeKeyCoverageTest` (messaging) — every semantic outcome variant and every
  `ReminderKind` maps to a catalog key present in **every** language bundle.
- No LLM-passthrough test — N/A under Option C (clarifyingQuestion stays discarded).

## Notes

- **Decisions captured 2026-06-12 (this `/spec-write` session):**
  1. **Backend-emits-semantic-DTOs rule** — `core` never emits a localization key,
     catalog key, finished chrome string, or view format; only filled DTOs with
     concrete typed fields. Frontend owns all view concerns including keys.
  2. **Tree text localized in `core`** — `core` owns algorithm texts only; it
     localizes them to the session language and ships concrete strings. Language
     arrives from the bot per turn; `core` resolves/persists the session language.
  3. **Option C for LLM free-text** (AC-VWRNDR.4) — keep the algorithm path
     deterministic; `clarifyingQuestion` stays discarded; LLM-passthrough deferred.
  4. **Scope B — total fix** — both the ingress path and the reminder/feedback
     outgoing path are corrected in this FR (strict separation of concerns end to end).
- **Supersedes FR-W03362 Variant A.** W03362 shipped the messaging-side catalog +
  the `catalog_key`-on-wire shape for reminders as an interim step; this FR replaces
  that shape with semantic DTOs and removes `catalog_key` from the wire and the DB.
- **No data migration.** M2 is pre-release with no live data; the `reminders.payload`
  schema change is a straight alter.
- **Refactor, not a behaviour change for users:** the same messages render; only
  *where* the chrome string is assembled (and *what* crosses the wire) changes from
  `core` to `messaging`. The user-visible diff is nil (covered by the contract +
  golden tests).
