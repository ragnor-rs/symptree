<!--
  scope: cross-cutting only

  `specs/requirements.md` § 2 captures cross-cutting functional
  requirements (auth scheme, observability surface, accessibility posture,
  etc.). Per-FR detail lives in `specs/frs/<id>.md` exclusively.

  AC IDs use `AC-<short-ULID>.<N>` in mode: none (last 6 chars of the
  ULID random portion). Literal `AC-1.1` / bare-digit-numbered shapes are
  rejected by /gate-check.
-->

# Requirements

## 1. Overview

symptree is a multi-tenant symptom-triage and appointment-booking platform for clinics, built on Kotlin/Ktor. A clinic runs its own messaging bot; patients describe a symptom in their own language, an LLM router maps that free text onto a deterministic decision tree (the LLM is auxiliary — the trees are authoritative), and the bot returns a triage conclusion (urgency, recommended specialist, optional tests with prep notes) and books the first available slot from the clinic's doctor schedule. Clinic staff manage doctors, schedules, sessions and bookings through a web admin panel, and symptree is the scheduler of record (HIS/HL7v2 is an optional external data source from M3, not on the M2 critical path; re-scoped 2026-06-11). M2 ships Telegram with RU + KK.

The decision trees are **content, not code**: the platform ships one synthetic demo tree and operators supply their own clinically-reviewed trees. symptree performs no diagnosis of its own and is not a medical device.

**Primary users:**
- **Patients** — interact via Telegram (M2) and WhatsApp (M3+) in their language (RU + KK initial, TR + UZ + KG + KA later).
- **Clinic admins / operators (B2B customers)** — onboard via admin REST API and the clinic-admin web panel (M2); manage doctors (specialist codes, weekly hours, days off), view symptomizer results, handle bookings, and configure services, optional external data sources, and which symptomizer algorithms are enabled.
- **Out-of-band: doctors themselves** are NOT direct symptree users. Where a clinic runs an existing HIS, symptree can federate it as an optional external data source from M3 (sync doctors / book via HL7v2), but the M2 pilot does not require any HIS — symptree owns the schedule internally.

## 2. Functional Requirements (cross-cutting only)

These five requirements span every M2 FR. Per-FR detail (entities, route shapes, ACs) lives in `specs/frs/<id>.md`; here we capture only the cross-cutting invariants every FR must honor.

### 2.1. Multi-tenancy by `clinic_id`

Every persistent row, bot session, LLM call, and adapter invocation carries a `clinic_id`. No query, route, or message handler in the request path operates without a `clinic_id` in scope. Cross-clinic queries (analytics, platform-admin tooling) live in a separate namespace explicitly marked as global; they are never reachable from a tenant-facing route. Sessions are stamped with their `clinic_id` at creation and that stamp is immutable for the session's lifetime.

### 2.2. Localization (i18n) — RU + KK in M2

Every user-facing string in the system — bot replies, error messages, validation feedback, algorithm tree node text, conclusion text, test-prep advice — flows through a message catalog (resource bundles per language). No hardcoded user-facing literals are permitted in routes, handlers, adapters, or templates. Algorithm tree JSON carries per-language text fields (concrete schema decided in `technical-spec.md` § 2). Each clinic declares a `default_language` and an `allowed_languages` set; user-session language is detected from the Telegram `language_code` field (or WhatsApp equivalent in M3) and may be overridden by the user mid-session. LLM calls carry the active language tag so output matches user-input language. M2 ships RU + KK; M3 adds TR alongside the WhatsApp adapter; UZ + KG + KA land later. Adding a language must require only catalog additions + algorithm-text augmentations, no code changes outside the i18n layer.

### 2.3. LLM auxiliary contract

The LLM router has **two and only two jobs**: (a) intent classification (does the user's message belong in the symptomizer flow, a booking flow, a service-info FAQ, or a fallback to a human operator) and (b) free-form-reply → tree-option mapping (when the user is mid-walk in a tree, translate their natural-language answer into the option_id of the current node). Every other diagnostic and routing decision is made by the deterministic expert-algorithm walker over the configured tree. All LLM output that affects state (intent class, option pick) is validated against the expert algorithm's view of valid next states before being applied; an LLM hallucination that names a nonexistent option is rejected and the user is re-prompted. The system MUST be safely degradable: if the LLM is unavailable, the bot falls back to button-only mode (the user picks options from inline keyboards rather than typing).

### 2.4. Data retention — 3-year dialog history

Bot sessions and the conversation turns within them persist for **3 years from session-end**. After 3 years, conversation rows are deleted; aggregate statistics (counts, anonymized symptom-category histograms) survive. PII fields (Telegram username, phone if collected, free-text symptom descriptions) are anonymized at retention-window expiry rather than the full rows being kept indefinitely. Retention is implemented as a scheduled job, not on-read, so cost of read scales with active data not historical.

### 2.5. Encryption — AES-256 at rest, TLS 1.3 in transit

All Postgres columns holding patient PII (free-text symptom descriptions, phone, Telegram username, conclusion text addressed to the patient) are encrypted at the application layer with AES-256-GCM. Encryption keys live in the deployment's secret store, NOT in the database. All external traffic (Telegram + WhatsApp + LLM API + HL7v2 over MLLP or HTTP, clinic-admin REST) uses TLS 1.3 minimum. HL7v2 over plain MLLP is rejected; the adapter requires MLLPS (TLS-wrapped) or HTTPS-wrapped HL7v2 only. Compliance with Russian 152-FZ and Kazakhstan personal-data laws is a downstream consequence and is also tracked under NFR-2 § 3.

## 3. Non-Functional Requirements

### NFR-1: Performance

Aspirational target: bot end-to-end reply ≤3 seconds at the median, ≤10 seconds at p95. Not gated in M2; tracked from first deploy. The dominant cost is the LLM call; mitigations live in `technical-spec.md` § 4 (timeouts, fallbacks, streaming).

### NFR-2: Security — 152-FZ + Kazakhstan PDn compliance

The platform must comply with Russian Federal Law 152-FZ "On Personal Data" and the Republic of Kazakhstan Law "On Personal Data and their Protection" — the two PDn regimes covering the M2 user base. Material consequences:
- **Data residency:** KZ user data must reside on infrastructure physically located in Kazakhstan. RU user data has equivalent residency requirements under 152-FZ. The deployment topology must support per-region data residency (deferred to M3+ for actual region rollout, but the architecture chosen in M2 must not preclude it).
- **Consent capture:** explicit user consent for processing health-related data must be captured on first interaction and re-confirmable.
- **DPIA:** a Data Protection Impact Assessment is required before production launch; tracked as a milestone gate, not an M2 ship blocker.
- **Right to export + delete:** patients may request export of their session history and deletion of their records; the retention job (§ 2.4) and an admin REST endpoint must support this.

### NFR-3: Availability — 99.9%

Bot-facing endpoints and clinic-admin REST endpoints target 99.9% monthly availability (~43 minutes downtime/month). Measured as: (HTTP 5xx + LLM unavailable fallback firings + Postgres unreachable events) / total requests. Excluded from the SLA: planned maintenance windows (announced 7 days in advance), and upstream booking-source unavailability — an external HIS/CRM data source, when configured (M3+) — propagated to the patient as "booking is temporarily unavailable, try again later" (the bot stays up, only the booking step degrades). The M2 internal scheduler has no such upstream.

## 4. Edge Cases

To be discovered during spec review + per-FR drafting. Empty at /spec-write time.

## Security / Abuse Cases

Filled in alongside per-FR drafting in `specs/frs/<id>.md`. Headline threats already known:

| Attacker Goal | Attack Vector | Mitigation |
|---------------|---------------|------------|
| Clinic A reads clinic B's session data | Crafted clinic-admin REST call with mismatched `clinic_id` | Every admin route validates `clinic_id` against the API key's owning clinic at the route boundary; FR for clinic-admin auth (M2) makes this a hard AC. |
| Patient evades triage by manipulating LLM into a specific conclusion (e.g., to upsell a test for a kickback) | Prompt-injection in free-form symptom text | LLM auxiliary contract (§ 2.3) — LLM output is validated against the tree-walker's view of legal next-state; LLM cannot name conclusions or specialists not reachable from the current node. |
| Adversary harvests symptom data + Telegram IDs for re-sale | Compromise of Postgres / leaked backup | At-rest AES-256 of PII columns (§ 2.5); backup encryption is a deployment requirement. |

## 5. Out of Scope

Project-wide non-goals over the M2 → M3 horizon (re-evaluate annually):

- **ORM / JPA / Spring frameworks** — raw JDBC + Ktor only. Deliberate consolidation.
- **DI frameworks (Koin / Dagger)** — wire dependencies by hand from `main()` until the surface justifies it.
- **Patient-direct B2C bot** — symptree is sold to clinics; a bot patients reach without a clinic behind it is a different product.
- **Preventive / dispensary-monitoring / gamification modules** — symptom triage is the primary flow; these are later additions, not M2 → M3 scope.
- **Website widget for clinic sites** — patient-facing channels are Telegram + WhatsApp (M2 → M3); a website chat widget is not. (The M2 clinic-admin panel adds a Node/Vite frontend module, but that is staff-facing.)
- **Native mobile apps** — messaging platforms are the patient surface; no native client is planned.
- **Clinical authoring of decision trees** — the platform ships the engine, the schema and one synthetic demo tree. Authoring and clinically reviewing real trees is the operator's responsibility.

The clinic-admin web UI ships in **M2** (FR-XKH348) as a Vite SPA — the project's first frontend module — consuming the clinic-user-authed REST surface (FR-MG7TSR). (Re-scoped 2026-06-11; previously deferred.) Patient-facing native/web apps remain out of scope per the bullets above.

## 6. Traceability Matrix

| Requirement | Implementation | Tests |
|-------------|----------------|-------|
| AC-KT4697.1..7 | `database/02-migrate-algorithms.sql`, `core/src/main/kotlin/com/symptree/core/symptomizer/{Algorithm,Walker,AlgorithmIngestionValidator,AlgorithmSeedLoader}.kt`, `core/src/main/kotlin/com/symptree/core/db/AlgorithmRepo.kt`, `core/src/main/resources/algorithms/symptom_{headache,chest_pain,throat_pain}_adult-v2.0.0.json` | `core/src/test/kotlin/com/symptree/core/symptomizer/{AlgorithmMigrationIT,AlgorithmDeserializationTest,AlgorithmIngestionValidatorTest,WalkerTest,AlgorithmResourcesTest,AlgorithmSeedLoaderIT,AlgorithmExhaustiveWalkTest}.kt`, `core/src/test/kotlin/com/symptree/core/db/AlgorithmRepoIT.kt` |
| AC-PWVTT4.1..10 | `database/03-migrate-sessions.sql`, `core/src/main/kotlin/com/symptree/core/symptomizer/{Session,SessionService,Prompt,LanguageNotAllowedException}.kt`, `core/src/main/kotlin/com/symptree/core/db/SessionRepo.kt`, `core/src/main/kotlin/com/symptree/core/llm/{Intent,LlmRouterClient,LlmRejectedException,RouterValidator,OpenAiLlmRouterClient}.kt`, `core/src/main/kotlin/com/symptree/core/crypto/PiiCipher.kt`, `core/src/main/kotlin/com/symptree/core/config/CoreConfig.kt`, `core/src/main/kotlin/com/symptree/core/CoreApp.kt` | `core/src/test/kotlin/com/symptree/core/symptomizer/{SessionServiceTest,SessionServiceFixtures}.kt`, `core/src/test/kotlin/com/symptree/core/db/SessionRepoIT.kt`, `core/src/test/kotlin/com/symptree/core/llm/{RouterValidatorTest,OpenAiLlmRouterClientTest,OpenAiLlmRouterClientConfigTest,FakeLlmRouterClient}.kt`, `core/src/test/kotlin/com/symptree/core/crypto/PiiCipherTest.kt` |
| AC-W3WDWH.1..10 | `messaging/src/main/kotlin/com/symptree/messaging/{MessagingMain,adapter/TelegramAdapter,client/CoreClient,config/MessagingConfig,core/IngressHandler,core/TelegramUpdateNormalizer,port/OutgoingAction,port/MenuOption}.kt`, `core/src/main/kotlin/com/symptree/core/routes/{BotIngressRoutes,BotIngressDtos}.kt` (chrome relocated to `messaging` by FR-VWRNDR) | `messaging/src/test/kotlin/com/symptree/messaging/{adapter/TelegramAdapterTest,core/IngressHandlerTest,core/TelegramUpdateNormalizerTest,core/TelegramFixtures}.kt`, `core/src/test/kotlin/com/symptree/core/routes/BotIngressRoutesTest.kt`, `core/src/test/kotlin/com/symptree/core/i18n/NoHardcodedLiteralsTest.kt`, `core/src/test/kotlin/com/symptree/core/integration/TelegramJourneyIT.kt` |
| AC-YDANPW.1..10 | `database/{04-migrate-doctors,05-migrate-doctor-schedule}.sql`, `core/src/main/kotlin/com/symptree/core/source/{MedicalDataSource,DataSourceRegistry,DoctorDirectory,SchedulingProvider,BookingProvider,DoctorRef,Slot,PatientRef,BookingResult}.kt`, `core/src/main/kotlin/com/symptree/core/source/internal/InternalDoctorDirectory.kt`, `core/src/main/kotlin/com/symptree/core/db/DoctorRepo.kt` | `core/src/test/kotlin/com/symptree/core/source/{DataSourceRegistryTest,InternalDoctorDirectoryTest,NoProviderNameLeakTest}.kt`, `core/src/test/kotlin/com/symptree/core/db/{DoctorRepoIT,DoctorScheduleRepoIT}.kt` |
| AC-W03362.1..12 | `database/{06-migrate-bookings,07-migrate-reminders}.sql`, `core/src/main/kotlin/com/symptree/core/source/internal/InternalScheduler.kt`, `core/src/main/kotlin/com/symptree/core/booking/{BookingService,SlotStrategy,FeedbackHandler}.kt`, `core/src/main/kotlin/com/symptree/core/scheduler/ReminderScheduler.kt`, `core/src/main/kotlin/com/symptree/core/db/{BookingRepo,ReminderRepo}.kt`, `messaging/src/main/kotlin/com/symptree/messaging/routes/BotInternalRoutes.kt` | `core/src/test/kotlin/com/symptree/core/{source/internal/InternalSchedulerTest,booking/{BookingServiceTest,SlotStrategyTest,FeedbackHandlerTest},db/BookingRepoIT,scheduler/ReminderSchedulerIT,integration/AutoBookingFlowIT}.kt` |
| AC-VWRNDR.1..8 | `core/src/main/kotlin/com/symptree/core/routes/{IngressOutcomeDto,BotIngressRoutes,BotIngressDtos}.kt`, `core/src/main/kotlin/com/symptree/core/scheduler/{ReminderScheduler,HttpReminderDispatcher}.kt`, `core/src/main/kotlin/com/symptree/core/db/ReminderRepo.kt`, `core/src/main/kotlin/com/symptree/core/booking/BookingService.kt`, `messaging/src/main/kotlin/com/symptree/messaging/port/IngressOutcome.kt`, `messaging/src/main/kotlin/com/symptree/messaging/core/IngressRenderer.kt`, `messaging/src/main/kotlin/com/symptree/messaging/i18n/{MessageCatalog,SlotFormatter}.kt`, `messaging/src/main/kotlin/com/symptree/messaging/outgoing/BotInternalOutgoingRoutes.kt`, `messaging/src/main/resources/i18n/{ru,kk}.yaml` (relocates the catalog; supersedes W03362 Variant A) | `core/src/test/kotlin/com/symptree/core/{routes/BotIngressRoutesTest,i18n/CoreHasNoChromeTest,scheduler/ReminderSchedulerIT,booking/BookingServiceTest}.kt`, `messaging/src/test/kotlin/com/symptree/messaging/{i18n/{MessageCatalogTest,BundleParityTest},core/{IngressRendererTest,OutcomeKeyCoverageTest,IngressHandlerTest},outgoing/BotInternalOutgoingRoutesTest}.kt` |
| AC-MG7TSR.1..9 | `database/09-migrate-clinic-users.sql`, `core/src/main/kotlin/com/symptree/core/auth/TokenService.kt`, `core/src/main/kotlin/com/symptree/core/db/ClinicUserRepo.kt`, `core/src/main/kotlin/com/symptree/core/model/ClinicUser.kt`, `core/src/main/kotlin/com/symptree/core/routes/admin/AdminRoutes.kt` (login + bearer auth gate + token-derived tenancy + doctor/schedule/session/booking routes — the spec's per-file split folded into one file + inline gate), `core/src/main/kotlin/com/symptree/core/scheduler/HttpBookingCancelNotifier.kt`, `core/src/main/kotlin/com/symptree/core/db/{DoctorRepo,SessionRepo,BookingRepo}.kt` (admin read/write methods), `core/src/main/kotlin/com/symptree/core/config/CoreConfig.kt` + `CoreApp.kt` (token secret/TTL + wiring), `messaging/src/main/kotlin/com/symptree/messaging/outgoing/BotInternalOutgoingRoutes.kt` + `messaging/src/main/resources/i18n/{ru,kk}.yaml` (`booking_cancelled` → `bot.booking.cancelled`) | `core/src/test/kotlin/com/symptree/core/{auth/TokenServiceTest,db/ClinicUserRepoIT,routes/admin/{AdminAuthRoutesTest,DoctorRoutesTest,ScheduleRoutesTest,SessionReadRoutesTest,BookingAdminRoutesTest,AdminErrorContractTest},integration/AdminMultiTenancySweepIT,scheduler/HttpBookingCancelNotifierTest}.kt` |
| AC-XKH348.1..9 | `admin-panel/` (Vite SPA: `src/{api,auth,i18n,screens/{doctors,schedule,sessions,bookings}}`, `package.json`, `README.md`) | `admin-panel/src/**/*.test.ts(x)` (component + fetch-wrapper + i18n-parity); manual smoke checklist in `admin-panel/README.md` |
