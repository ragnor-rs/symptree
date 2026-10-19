# Technical Specification

## 1. Architecture

### System Overview

Two independently-deployable JVM services connected only by HTTP:

- **core** — Ktor 3.x REST API on Netty + Postgres (raw JDBC). Hosts the symptomizer engine, the LLM router, the clinic-admin REST surface (machine API-key + human clinic-user auth), the `MedicalDataSource` abstraction (internal scheduler in M2), the session store, and the 24h reminder scheduler. **It owns no view layer** — the i18n catalog and all chrome live in `messaging` (FR-VWRNDR); `core` localizes only its own algorithm-tree text.
- **messaging** — Telegram + WhatsApp bots. M2 wires Telegram only; the WhatsApp adapter is shape-locked but bodyless (`TODO()`). Talks to `core` over HTTP only.
- **admin-panel** — Vite SPA (clinic-admin web UI, M2 re-scope). Pure HTTP client of `core`'s `/api/v1/admin/**`; no Gradle/compile coupling. See FR-XKH348.

M2 ships 8 FRs (see `specs/frs/`):

```
M3WYNS — multi-tenant clinic model + clinic-admin REST
KT4697 — algorithm catalog + multilingual tree walker
PWVTT4 — symptomizer session + LLM router (intent + free-form parsing)
W3WDWH — Telegram bot patient journey (RU + KK)
YDANPW — medical data-source abstraction + canonical doctor catalog
W03362 — internal scheduler + auto-booking (first-available)
MG7TSR — clinic-user auth + clinic-admin panel REST
XKH348 — clinic-admin panel (web UI)
```

(Booking re-scoped 2026-06-11: symptree is the scheduler of record via an internal `MedicalDataSource`; the operator-assisted model FR-4GCYJK is archived and the HL7v2 mock FR-TNWP9R + automated-external-booking FR-2VQ7CA move to M3 as additional data sources.)

### Directory Structure

```
symptree/
├── build.gradle.kts              # Root: applies kotlin/detekt/dep-analysis to subprojects
├── settings.gradle.kts           # Plugin mgmt + include("core"), include("messaging")
├── gradle.properties
├── detekt.yml                    # Relaxed style rules (copied from MMP reference)
├── database/                     # M2: Postgres migrations (flat NN-migrate-<slug>.sql)
│   ├── 01-migrate-clinics.sql
│   ├── 02-migrate-algorithms.sql
│   ├── 03-migrate-sessions.sql
│   ├── 04-migrate-sessions-active-only-unique.sql  # active-session partial unique (PWVTT4)
│   ├── 05-migrate-doctors.sql           # doctor_identity + doctors (YDANPW)
│   ├── 06-migrate-doctor-schedule.sql   # doctor_weekly_hours + doctor_time_off (YDANPW)
│   ├── 07-migrate-bookings.sql          # source-agnostic bookings (W03362)
│   ├── 08-migrate-reminders.sql         # reminders (W03362)
│   └── 09-migrate-clinic-users.sql      # clinic_users (MG7TSR)
├── core/
│   ├── build.gradle.kts
│   └── src/{main,test}/kotlin/com/symptree/core/
│       ├── CoreApp.kt
│       ├── config/CoreConfig.kt
│       ├── plugins/{Serialization,CallLogging,StatusPages}.kt
│       ├── routes/                # /health (M1) + /api/v1/* (M2)
│       │   ├── HealthRoutes.kt
│       │   ├── AdminClinicRoutes.kt
│       │   ├── AdminAlgorithmRoutes.kt
│       │   ├── BotIngressRoutes.kt
│       │   └── admin/             # clinic-user-authed panel surface (MG7TSR)
│       │       ├── AdminAuthRoutes.kt
│       │       ├── DoctorRoutes.kt
│       │       ├── ScheduleRoutes.kt
│       │       ├── SessionReadRoutes.kt
│       │       └── BookingAdminRoutes.kt
│       ├── auth/                  # clinic-user login + token (MG7TSR)
│       │   ├── ClinicUserAuth.kt
│       │   └── TokenService.kt
│       ├── db/                    # raw JDBC
│       │   ├── PostgresDatabase.kt
│       │   ├── ClinicRepo.kt
│       │   ├── AlgorithmRepo.kt
│       │   ├── SessionRepo.kt
│       │   ├── DoctorRepo.kt
│       │   ├── ClinicUserRepo.kt
│       │   ├── ReminderRepo.kt
│       │   └── BookingRepo.kt
│       ├── symptomizer/           # tree walker
│       │   ├── Algorithm.kt        # also carries sealed Node + QuestionNode + ConclusionNode + Option (co-located for serializer wiring)
│       │   ├── Walker.kt
│       │   ├── AlgorithmIngestionValidator.kt
│       │   └── AlgorithmSeedLoader.kt
│       ├── llm/                   # LLM router
│       │   ├── LlmRouterClient.kt # port
│       │   ├── OpenAiLlmRouterClient.kt # default impl
│       │   ├── Intent.kt
│       │   └── RouterValidator.kt
│       ├── source/               # MedicalDataSource abstraction (YDANPW)
│       │   ├── MedicalDataSource.kt      # per-clinic composition of capability providers
│       │   ├── DataSourceRegistry.kt     # clinic's source set + federating aggregators
│       │   ├── DoctorDirectory.kt        # capability interface
│       │   ├── SchedulingProvider.kt     # capability interface
│       │   ├── BookingProvider.kt        # capability interface
│       │   ├── DoctorRef.kt  Slot.kt  PatientRef.kt  BookingResult.kt  # source-agnostic types
│       │   ├── internal/         # the only M2 source impl
│       │   │   ├── InternalDoctorDirectory.kt
│       │   │   └── InternalScheduler.kt  # SchedulingProvider + BookingProvider
│       │   └── hl7v2/            # DEFERRED to M3 (FR-TNWP9R / FR-2VQ7CA)
│       │       ├── Hl7v2Provider.kt      # SchedulingProvider + BookingProvider
│       │       └── Hl7v2Codec.kt         # SIU^S12, ADT^A04 builders + parsers
│       ├── scheduler/             # 24h reminder scheduler
│       │   └── ReminderScheduler.kt  # emits a semantic ReminderDue DTO (FR-VWRNDR)
│       └── booking/
│           ├── BookingService.kt        # findFirstAvailable + confirm/decline (W03362)
│           ├── SlotStrategy.kt          # FirstAvailable (round_robin/least_busy → M3)
│           └── FeedbackHandler.kt       # post-visit 1–5 (W03362)
├── messaging/
│   ├── build.gradle.kts
│   └── src/{main,test}/kotlin/com/symptree/messaging/
│       ├── MessagingMain.kt
│       ├── config/MessagingConfig.kt
│       ├── port/                  # MessagingPort + DTOs + IngressOutcome mirror (M1 + VWRNDR)
│       ├── i18n/                  # the single view-layer catalog (FR-VWRNDR)
│       │   ├── MessageCatalog.kt        # unified ingress + reminder/feedback chrome
│       │   ├── SlotFormatter.kt         # dd.MM HH:mm / HH:mm clinic-local formatting
│       │   └── resources i18n/{ru,kk}.yaml
│       ├── adapter/               # TelegramAdapter (M2 wired); WhatsAppAdapter (M3 stub)
│       ├── core/                  # Handlers + IngressRenderer (semantic→chrome, VWRNDR)
│       ├── outgoing/              # /bot-internal/outgoing (shared-secret gated, W03362/VWRNDR)
│       └── client/                # CoreClient (M2: full surface against backend's /api/v1)
└── admin-panel/                   # Vite + React + TS SPA, clinic-admin web UI (XKH348)
    ├── build.gradle.kts           # `base` + Exec tasks (npm ci/build/test) → part of ./gradlew build & test
    ├── package.json               # Node toolchain; no Kotlin/compile coupling, HTTP-only at runtime
    ├── vite.config.ts             # /api → localhost:8080 proxy + Vitest config
    ├── README.md                  # dev server + build + manual smoke checklist
    └── src/
        ├── api/                   # ApiClient (Bearer attach + 401→login) + wire types
        ├── auth/                  # in-memory token + AuthProvider
        ├── i18n/                  # ru/kk catalog + provider (compile-time + runtime parity)
        ├── components/            # Layout, UrgencyBadge, ConfirmDialog
        ├── lib/                   # pure helpers: schedule validation, formatting
        └── pages/                 # Login, Doctors, ScheduleEditor, Sessions, SessionDetail, Bookings
```

The `messaging` module does NOT depend on `core`. They communicate over HTTP only. Each module produces its own `shadowJar` fat jar.

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| ORM | None — raw JDBC | Matches MMP-reference style; SQL stays grep-able; no ORM cache pitfalls. |
| Connection pool | None initially | Add HikariCP when measured load demands it. |
| DI framework | None — hand-wired in `main()` | Two modules too small for Koin/Dagger overhead. |
| Bot/backend coupling | HTTP only | Mirrors MMP `dressmibot ↔ backend` split. Independent deploy/scale. |
| Bot platform abstraction | `port/MessagingPort` + per-platform `adapter/` | Adding a new platform = one adapter file, zero handler changes. |
| Serialization | `kotlinx-serialization-json` | Idiomatic Kotlin; multiplatform-ready. |
| Multi-tenancy | `clinic_id` carried explicitly on every row + every request | No row-level security in Postgres yet; enforced in the route boundary and the repo signatures. |
| Algorithm storage | Postgres JSONB column for the tree body, normalized metadata columns alongside | Tree shape is irregular and versioned; JSONB allows schema migrations on individual algorithms without DDL. |
| Algorithm i18n | `text: {ru: "...", kk: "..."}` nested object on each node | Single source-of-truth tree per algorithm version; scales to N languages by adding catalog entries, no schema change. |
| LLM coupling | `LlmRouterClient` port; M2 default impl = OpenAI GPT-4 / 4-Turbo | Vendor abstraction so Claude / Llama can swap in later (per spec §14.5.2). Default chosen for spec's "High" recommendation + multilingual quality. |
| LLM safety | All LLM output validated against the algorithm walker before applying | The LLM is auxiliary (requirements.md § 2.3). Intent picks not in `{symptom, booking, faq, fallback}` rejected; option picks not in current node's option set rejected. |
| Session storage | One row per session; `history` is JSONB | Current-state reads dominate over replay analytics in M2; JSONB blob is the simpler shape. |
| Care-provider data | `MedicalDataSource` = composition of capability providers (`DoctorDirectory`/`SchedulingProvider`/`BookingProvider`); a clinic owns a set of sources | Lets internal + HL7/CRM doctors federate under one panel; any capability binds to a different backend. Replaces the narrow `ClinicAdapter`. M2 ships the INTERNAL source only. |
| Scheduler of record | symptree owns the calendar internally (doctor hours + time-off + bookings); first-available computed locally | Removes the 6–12-month HIS/HL7 dependency from the pilot critical path. HL7/CRM sources are additive later. |
| Double-booking guard | Postgres `EXCLUDE USING gist` partial constraint scoped to the internal source | Write-side equivalent of the reminder poll's `FOR UPDATE SKIP LOCKED`; external calendars are the vendor's authority. |
| Admin panel | Vite SPA over `/api/v1/admin/**`, decoupled like the bot module (HTTP only) | First frontend; mirrors bot↔core decoupling. Node toolchain isolated under `admin-panel/`. |
| Clinic-user auth | bearer token from `/admin/login`; `clinic_users` (bcrypt); `clinic_id` from token | Browser/human boundary the machine API key doesn't cover; establishes per-operator identity for the deferred chat. |
| HL7v2 (deferred → M3) | SIU^S12 + ADT^A04 + AA/AE/AR ACK over MLLPS/HTTPS-wrap, inside `source/hl7v2/` | Not on M2 critical path (booking is internal). Lands as the second `BookingProvider`. Plain MLLP rejected per § 2.5. |
| 24h reminder transport | In-process coroutine scheduler inside `core`, polls `reminders` table every 60s | Single process for M2 volume. Migrate to a separate worker when pilot volume forces it (probably M4+). |
| Encryption surface | AES-256-GCM at application layer on PII columns; keys in env / secret store | Postgres TDE not assumed available on every deployment target (e.g., Yandex Cloud Managed Postgres lacks per-column TDE). |

## 2. Data Model

### 2.1 Postgres schema (M2)

Tables across migrations `01–08`. All have `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; mutable rows have `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()` with a trigger. The booking schema is **source-agnostic** — no provider-type names (`hl7_*`) in any column (FR-YDANPW leak invariant; `NoProviderNameLeakTest` sweeps `database/*.sql`).

```sql
-- 01-migrate-clinics.sql
CREATE TABLE clinics (
  id              BIGSERIAL PRIMARY KEY,
  slug            TEXT NOT NULL UNIQUE,
  name            TEXT NOT NULL,
  default_language TEXT NOT NULL,           -- ISO code: 'ru' | 'kk' | 'tr' | 'uz' | 'kg' | 'ka'
  allowed_languages TEXT[] NOT NULL,
  config          JSONB NOT NULL DEFAULT '{}'::jsonb,  -- service catalog, per-source connection config, API key hash, preferences. preferences.timezone = IANA zone id (e.g. 'Asia/Tbilisi'), default 'UTC' — anchors doctor_weekly_hours wall-clock to UTC (see § Scheduling time model). preferences.min_lead_hours = minimum auto-booking lead time in hours, default 24 (FR-W03362 AC.13). preferences.first_day_of_week = the admin-panel weekly-hours grid's first day (0=Sun..6=Sat), default 1 (Monday) — display-ordering only, exposed via GET /api/v1/admin/clinic-settings (FR-XKH348)
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 02-migrate-algorithms.sql
CREATE TABLE algorithms (
  id              BIGSERIAL PRIMARY KEY,
  algorithm_key   TEXT NOT NULL,            -- e.g. 'symptom_demo_adult'
  version         TEXT NOT NULL,            -- e.g. '2.1.0'
  metadata        JSONB NOT NULL,           -- name, category, age_group, estimated_time
  tree            JSONB NOT NULL,           -- {entry_point, nodes: {...multilingual...}}
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (algorithm_key, version)
);
-- M3 (FR-A4ZDFW): adds nullable `owner_clinic_id BIGINT REFERENCES clinics(id) ON DELETE CASCADE`
-- (NULL = platform-global, preserves all M2 rows; non-NULL = clinic-private) and re-keys the
-- unique constraint to `(owner_clinic_id, algorithm_key, version)` so a global and per-clinic
-- private rows can share an `algorithm_key` without collision. Forward migration only; no backfill.
CREATE TABLE clinic_algorithms (
  clinic_id       BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
  algorithm_id    BIGINT NOT NULL REFERENCES algorithms(id) ON DELETE RESTRICT,
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (clinic_id, algorithm_id)
);

-- 03-migrate-sessions.sql
CREATE TABLE sessions (
  id                   BIGSERIAL PRIMARY KEY,
  clinic_id            BIGINT NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
  platform             TEXT NOT NULL,        -- 'telegram' | 'whatsapp'
  platform_user_id     TEXT NOT NULL,        -- Telegram user id or WA phone
  language             TEXT NOT NULL,
  current_algorithm_id BIGINT REFERENCES algorithms(id),  -- nullable: pre-pick state
  current_node_id      TEXT,                 -- nullable: pre-pick state
  history              JSONB NOT NULL DEFAULT '[]'::jsonb,  -- [{turn, node_id, user_text_encrypted, llm_intent, picked_option, ts}, ...]
  status               TEXT NOT NULL DEFAULT 'active',  -- 'active' | 'concluded' | 'abandoned'
  retention_anonymize_at TIMESTAMPTZ,        -- set when status flips to terminal
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (platform, platform_user_id, clinic_id, status)  -- one active session per (user, clinic, platform)
);
CREATE INDEX sessions_clinic_active ON sessions(clinic_id) WHERE status = 'active';

-- 05-migrate-doctors.sql  (FR-YDANPW: canonical doctor catalog)
CREATE TABLE doctor_identity (              -- canonical grouping (1:1 with a doctor row in MVP)
  id              BIGSERIAL PRIMARY KEY,
  clinic_id       BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
  display_name    TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE doctors (                      -- per-source projection row
  id              BIGSERIAL PRIMARY KEY,
  clinic_id       BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
  identity_id     BIGINT NOT NULL REFERENCES doctor_identity(id) ON DELETE RESTRICT,
  source_id       TEXT NOT NULL,            -- 'internal' in M2; vendor source id later
  external_id     TEXT NOT NULL,            -- id within the source (= doctors.id for internal)
  full_name       TEXT NOT NULL,
  specialist_codes TEXT[] NOT NULL,         -- matched against ConclusionNode.specialist
  slot_minutes    INT NOT NULL DEFAULT 30,
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  deleted_at      TIMESTAMPTZ,              -- soft-delete (full-relist sync expires absent rows)
  synced_at       TIMESTAMPTZ,              -- per-source watermark (forward-investment)
  sync_cursor     TEXT,                     -- incremental-sync cursor (forward-investment)
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (source_id, external_id)           -- composite natural key
);

-- 06-migrate-doctor-schedule.sql  (FR-YDANPW)
CREATE TABLE doctor_weekly_hours (          -- multiple rows / (doctor, dow) = split shifts; none = day off
  id              BIGSERIAL PRIMARY KEY,
  doctor_id       BIGINT NOT NULL REFERENCES doctors(id) ON DELETE CASCADE,
  day_of_week     SMALLINT NOT NULL,        -- 0=Sun .. 6=Sat, evaluated in the clinic's local timezone
  start_time      TIME NOT NULL,            -- LOCAL wall-clock (clinic timezone), NOT UTC
  end_time        TIME NOT NULL,            -- LOCAL wall-clock (clinic timezone), NOT UTC
  CHECK (end_time > start_time)
);
CREATE TABLE doctor_time_off (              -- one-off absences / vacation
  id              BIGSERIAL PRIMARY KEY,
  doctor_id       BIGINT NOT NULL REFERENCES doctors(id) ON DELETE CASCADE,
  start_at        TIMESTAMPTZ NOT NULL,
  end_at          TIMESTAMPTZ NOT NULL,
  reason          TEXT,
  CHECK (end_at > start_at)
);

-- 07-migrate-bookings.sql  (FR-W03362: source-agnostic; needs btree_gist)
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE bookings (
  id              BIGSERIAL PRIMARY KEY,
  clinic_id       BIGINT NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
  session_id      BIGINT NOT NULL REFERENCES sessions(id) ON DELETE RESTRICT,
  source_id       TEXT NOT NULL,            -- 'internal' | vendor source id
  external_doctor_id TEXT NOT NULL,         -- doctor ref within the owning source
  doctor_identity_id BIGINT REFERENCES doctor_identity(id),  -- display/aggregation (nullable)
  specialist_code TEXT NOT NULL,
  slot_at         TIMESTAMPTZ NOT NULL,
  slot_end        TIMESTAMPTZ NOT NULL,
  external_appointment_id TEXT,             -- owning source's appt ref; NULL for internal (was hl7_appointment_id)
  status          TEXT NOT NULL DEFAULT 'confirmed',  -- 'confirmed' | 'cancelled' | 'completed'
  feedback_score  SMALLINT,                 -- 1..5, post-visit
  feedback_text   TEXT,
  icd_code        TEXT,                     -- populated after visit (M3+)
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  -- double-booking prevented only where symptree owns the calendar (internal source)
  EXCLUDE USING gist (
    external_doctor_id WITH =,
    tstzrange(slot_at, slot_end) WITH &&
  ) WHERE (source_id = 'internal' AND status = 'confirmed')
);
CREATE INDEX bookings_clinic_session ON bookings(clinic_id, session_id);

-- 08-migrate-reminders.sql  (FR-W03362; reused from archived FR-4GCYJK)
CREATE TABLE reminders (
  id              BIGSERIAL PRIMARY KEY,
  clinic_id       BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
  session_id      BIGINT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  due_at          TIMESTAMPTZ NOT NULL,
  kind            TEXT NOT NULL,            -- 'pre_visit_24h' | 'post_visit_feedback' | 'same_day'
  payload         JSONB NOT NULL,           -- semantic: {platform_user_id, language, kind, doctor_name, slot_at, clinic_zone} (FR-VWRNDR)
  status          TEXT NOT NULL DEFAULT 'pending',  -- 'pending' | 'sent' | 'failed' | 'cancelled'
  sent_at         TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX reminders_due_pending ON reminders(due_at) WHERE status = 'pending';

-- 09-migrate-clinic-users.sql  (FR-MG7TSR: human/browser auth for the admin panel)
CREATE TABLE clinic_users (
  id              BIGSERIAL PRIMARY KEY,
  clinic_id       BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
  email           TEXT NOT NULL,
  password_hash   TEXT NOT NULL,            -- bcrypt (never plaintext, never logged)
  display_name    TEXT NOT NULL,
  role            TEXT NOT NULL DEFAULT 'operator',  -- 'admin' | 'operator'
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (clinic_id, email)
);

-- 10-migrate-pending-bookings.sql  (FR-W03362 AC.5: transient awaiting-confirm state)
CREATE TABLE pending_bookings (
  id                  BIGSERIAL PRIMARY KEY,
  clinic_id           BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
  session_id          BIGINT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  specialist_code     TEXT NOT NULL,
  source_id           TEXT NOT NULL,            -- 'internal' in M2
  external_doctor_id   TEXT NOT NULL,            -- denormalized proposed-slot doctor ref
  doctor_identity_id  BIGINT REFERENCES doctor_identity(id),
  doctor_display_name TEXT NOT NULL,
  slot_at             TIMESTAMPTZ NOT NULL,
  slot_end            TIMESTAMPTZ NOT NULL,
  decline_count       INT NOT NULL DEFAULT 0,   -- N-decline fallback (AC-W03362.7)
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (slot_end > slot_at),
  UNIQUE (session_id)                           -- one pending proposal per session; a re-propose upserts
);
```

PII columns flagged for application-layer AES-256-GCM encryption (per § 2.5 of requirements):
- `clinics.config` — the API key hash field within the JSONB (other config is non-PII)
- `sessions.history` — every `user_text_encrypted` JSONB field within
- `sessions.platform_user_id` — Telegram user IDs are PII in the EU sense
- `bookings.external_appointment_id` if an external vendor uses a national ID as the key (config-dependent; NULL for the internal source)

Not encrypted but hashed: `clinic_users.password_hash` is bcrypt (a one-way hash, not reversible encryption — never store or log the plaintext).

### 2.2 Algorithm tree JSON schema

Two-node worked example. Content is synthetic placeholder text — the repository
ships one demo tree (`symptom_demo_adult`) and no clinical trees; operators
supply their own clinically-reviewed content.

```json
{
  "algorithm_id": "symptom_demo_adult",
  "version": "2.1.0",
  "metadata": {
    "name": {"ru": "ДЕМО: пример дерева решений", "kk": "ДЕМО: шешім ағашының мысалы"},
    "category": "demo",
    "age_group": "adult",
    "estimated_time_minutes": 3
  },
  "entry_point": "q1",
  "nodes": {
    "q1": {
      "type": "question",
      "text": {"ru": "ДЕМО, вопрос 1: признак A присутствует?", "kk": "ДЕМО, 1-сұрақ: A белгісі бар ма?"},
      "options": [
        {"value": "yes", "label": {"ru": "Да", "kk": "Иә"}, "next": "demo_emergency"},
        {"value": "no",  "label": {"ru": "Нет", "kk": "Жоқ"}, "next": "q2"}
      ]
    },
    "demo_emergency": {
      "type": "conclusion",
      "urgency": "emergency",
      "diagnosis": {"ru": "ДЕМО-исход: пример экстренного исхода", "kk": "ДЕМО-қорытынды: шұғыл қорытынды мысалы"},
      "action": {"ru": "ДЕМО: пример экстренного действия", "kk": "ДЕМО: шұғыл әрекет мысалы"},
      "specialist": null,
      "tests": [],
      "additional_tests": [],
      "preparation": {},
      "clinic_action": "bypass_appointment",
      "follow_up_days": null
    }
  }
}
```

`text` and `diagnosis`/`action` are always objects keyed by ISO language code. Required keys per node = the clinic's `allowed_languages`. Tree-validation gate at ingestion time: every text-bearing field has every required language; missing language = reject. Adding a language to a clinic requires augmenting every enabled algorithm's text fields (or auto-falling-back to `default_language` with a `[needs translation]` marker — pick a policy in FR-i18n).

## 3. API / Interface Design

### 3.1 Backend HTTP — public surface

- `GET /health` → `{"status":"ok"}` (no auth) — M1
- `POST /api/v1/clinics` — clinic onboarding; platform-admin auth (static API key in M2)
- `GET /api/v1/clinics/{slug}` — clinic config; platform-admin OR clinic-admin (matching slug)
- `PUT /api/v1/clinics/{slug}/config` — clinic config; platform-admin OR clinic-admin. Strips `api_key_hash` from the incoming patch silently to preserve the "rotation only via re-POST" invariant; INFO-logs a redacted before/after diff with `clinic_id` and never logs the hash itself.
- `POST /api/v1/clinics/{slug}/algorithms/{algorithm_key}/enable` — toggle algorithm on; platform-admin OR clinic-admin; idempotent
- `POST /api/v1/clinics/{slug}/algorithms/{algorithm_key}/disable` — toggle algorithm off; platform-admin OR clinic-admin; idempotent
- `POST /api/v1/clinics/{slug}/bot/ingress` — bot → backend bridge: bot module posts incoming Telegram updates here; backend returns reply payload
- `POST /api/v1/clinics/{slug}/reminders/_test` — clinic-admin: send a test reminder to verify wiring

Clinic-admin **panel** surface (FR-MG7TSR) — human/browser auth, distinct from the machine API key:
- `POST /api/v1/admin/login` → bearer token (carries `clinic_id` + `clinic_user_id` + `role`)
- `POST/GET/PATCH/DELETE /api/v1/admin/doctors` + `PUT /api/v1/admin/doctors/{id}/weekly-hours` + `…/time-off` — doctor + schedule CRUD
- `GET /api/v1/admin/sessions` + `/{id}` (+ `/export`, `DELETE` anonymize) — symptomizer-results read (PII decrypted server-side). The detail (`/{id}`) carries a semantic `conclusion` block for concluded sessions (FR-XKH348): `urgency`/`clinic_action`/`specialist` codes + algorithm-authored `diagnosis`/`action`/`tests[].prep` localized to the session language.
- `GET /api/v1/admin/specialist-codes` — the clinic's enabled algorithms' specialist codes (deduped, semantic), driving the SPA's doctor specialist-code selection (FR-XKH348 AC-XKH348.3).
- `GET /api/v1/admin/doctors/{id}/weekly-hours` — read the doctor's persisted weekly-hours set (same `{weekly_hours:[…]}` wire shape as the `PUT`), so the SPA's schedule editor loads real hours instead of opening empty (FR-XKH348). `day_of_week` is the canonical **0=Sun..6=Sat** (Postgres `EXTRACT(DOW)`).
- `GET /api/v1/admin/clinic-settings` — the clinic's display settings (semantic): `timezone` (IANA, default `UTC`), `first_day_of_week` (0=Sun..6=Sat, **default Monday = 1**), `default_language`, `allowed_languages`. The SPA orders the weekly-hours grid per the clinic's first day; stored in `config.preferences` (FR-XKH348).
- `GET /api/v1/admin/bookings` + `PATCH /api/v1/admin/bookings/{id}` — booking list + cancel
- `GET /api/v1/admin/algorithms` — the clinic's visible algorithm set (globals + own private), each with `enabled` + `owned` flags; any clinic-user role; list view (no tree bodies). **M3 (FR-A4ZDFW)**
- `POST /api/v1/admin/algorithms` — upload a private algorithm; `multipart/form-data` (single `.json` file part, 256KB cap); role `admin`; inserts with `owner_clinic_id` = token clinic; 413/422/409 on cap/validation/collision. First multipart endpoint in the backend. **M3 (FR-A4ZDFW)**
- `DELETE /api/v1/admin/algorithms/{algorithm_key}` — delete the clinic's own private algorithm; role `admin`; scoped by `owner_clinic_id` (globals/other tenants unreachable → 404). **M3 (FR-A4ZDFW)**
- `POST /api/v1/admin/algorithms/{algorithm_key}/enable|disable` — toggle enablement on the admin (bearer) surface; role `admin`; delegates to the M2 `setAlgorithmEnabled`. Mirrors the `X-API-Key` clinic-surface toggles below for clinic-staff use. **M3 (FR-A4ZDFW)**

The Vite SPA (FR-XKH348) is the only consumer; `clinic_id` is taken from the token, never from a path/body param (cross-clinic → 403). The two FR-XKH348 read endpoints emit semantic DTOs only — the SPA owns chrome localization (FR-VWRNDR boundary).

Auth: clinic-admin (machine) = static API key per clinic (`X-API-Key` header; M2; rotate by re-issuing); clinic-user (human) = bearer token from `/admin/login` validated on `/api/v1/admin/**`; platform-admin = a single env-injected key (`PLATFORM_ADMIN_API_KEY`; hard-required at startup — `CoreConfig.fromEnv()` refuses to construct without it). For tenant-scoped routes (everything under `/api/v1/clinics/{slug}/...`), the auth ladder is: missing key → 401; valid clinic-admin key for a different (or unknown) slug → 403 (never 404 — don't leak existence); platform-admin + unknown slug → 404. Bearer-token + per-user RBAC deferred to a later milestone.

### 3.2 Backend HTTP — internal contract with messaging

`messaging` posts every incoming platform update to `POST /api/v1/clinics/{slug}/bot/ingress`. Request body carries the normalized `IncomingMessage` (see `messaging/src/.../port/IncomingMessage.kt`).

**The response is a semantic view model, not view artifacts (FR-VWRNDR).** The wire is a backend↔frontend contract carrying *semantic data* — `core` emits *what happened* as a typed `IngressResponse { lang, outcomes: List<IngressOutcome> }`, where each outcome is one of 14 named variants (`Welcome`, `LanguagePicker`, `GoalPrompt`, `NextQuestion`, `Reprompt`, `ButtonOnly`, `Conclusion`, `ConfirmSlot`, `NoSlots`, `BookingConfirmed`, `BookingContactClinic`, `NoAlgorithmMatch`, `LanguageChanged`, `LanguageNotAllowed`) carrying **concrete typed fields only**. `core` never emits a localization key, catalog key, finished chrome string, or view format. The one presentation `core` owns is **algorithm-tree text** (question/diagnosis/action/test names/option labels) — `core`-owned multilingual domain data, localized **by `core`** to the resolved session `lang` (carried in the envelope) and shipped as concrete strings. The frontend's `IngressRenderer` maps each variant to its **own** catalog keys, formats datetimes + selects urgency icons, and assembles the platform `OutgoingAction`s its `MessagingPort` replays.

The backend never holds a connection to Telegram or WhatsApp. The bot module owns all platform I/O **and all view concerns**.

### 3.3 `MessagingPort` (messaging) — unchanged from M1 scaffold

The M1 scaffold's `port/MessagingPort` interface is the bot-side surface; M2 wires up the existing methods (`sendText`, `sendPhoto`, `sendMenu`, etc.) in `TelegramAdapter`. WhatsApp adapter stays `TODO()`.

### 3.4 `MedicalDataSource` abstraction (FR-YDANPW)

Replaces the former HL7v2-flavored `ClinicAdapter` port. A clinic's care-provider data is exposed through a **composition of capability providers**, each independently backed; a clinic owns a *set* of `MedicalDataSource`s that aggregators federate over. No provider-type name appears in these canonical types (the leak invariant — `NoProviderNameLeakTest` sweeps `source/` excluding `source/hl7v2/`).

```kotlin
// capability providers — thin, independently implementable (interface segregation)
interface DoctorDirectory    { suspend fun listDoctors(clinicId: Long): List<DoctorRef> }
interface SchedulingProvider { suspend fun availability(doctor: DoctorRef, window: ClosedRange<Instant>): List<Slot> }
interface BookingProvider {
    suspend fun book(slot: Slot, patient: PatientRef): BookingResult
    suspend fun cancel(ref: ExternalApptRef)
    // M3+: reschedule, recordIcd
}

// a MedicalDataSource binds a provider per capability (any may be null)
class MedicalDataSource(
    val id: SourceId,                     // per-instance primary key (many sources may share a kind)
    val kind: SourceKind,                 // M2: INTERNAL only — adapter + connection-params discriminator
    val directory: DoctorDirectory?,      // null ⇒ contributes no doctors (a booking-only source)
    val scheduling: SchedulingProvider?,
    val booking: BookingProvider?,
)
class DataSourceRegistry(val sources: List<MedicalDataSource>)  // M2: exactly one INTERNAL source

// source-agnostic types
data class DoctorRef(val sourceId: SourceId, val externalId: String, val identityId: Long,
                     val displayName: String, val specialistCodes: List<String>)
data class Slot(val doctorRef: DoctorRef, val start: Instant, val end: Instant)
data class PatientRef(val externalId: String?, val displayName: String?, val phone: String?, val languageCode: String)
data class BookingResult(val externalRef: String?, val confirmedSlot: Slot)  // externalRef NULL for internal bookings
```

**M2 ships only the `INTERNAL` source** (`source/internal/`): `InternalDoctorDirectory` over `DoctorRepo`, and `InternalScheduler` implementing both `SchedulingProvider` (first-available computed from `doctor_weekly_hours − doctor_time_off − confirmed bookings`) and `BookingProvider` (insert a `bookings` row; the partial `EXCLUDE` constraint prevents double-booking). `externalRef` is NULL for internal bookings.

`Hl7v2Provider` + `Hl7v2Codec` (FR-TNWP9R, deferred to M3) become the second `SchedulingProvider`/`BookingProvider` under `source/hl7v2/`, and a REST/CRM source supplies `DoctorDirectory` (HL7v2 cannot — it is not a directory protocol). HL7-specific names are correct *inside* `source/hl7v2/` and the wire codec; nowhere else.

**Source-kind taxonomy + connection model (M3 direction — recorded here so M2 ships ready, per FR-YDANPW's schema-first mandate; not built in M2).** `SourceKind` is the **adapter + connection-params discriminator** — it selects which adapter backs a source and how that source's connection parameters are parsed. It is orthogonal to `SourceId`, the source's **per-instance primary key**: a clinic may run several sources of the same kind (e.g. two hospital information systems), each with its own `SourceId` and connection params. The kinds:

- `INTERNAL` — the clinic's own canonical catalog (the `doctors`/`doctor_identity` tables); no external connection, no params. **The only kind M2 ships** (`SourceKind { INTERNAL }`).
- `HL7V2` (M3) — a *standardized format* family: **one adapter backs many HIS vendors**, differentiated only by connection params (host, MLLP port, sending/receiving facility). One kind, many instances.
- CRM kinds (M3, per-integration) — REST CRMs are **not** a standard, so there is no single reusable adapter and **no generic `REST_CRM` kind**: each integration is its own kind + adapter + params schema (`BITRIX24`, `AMOCRM`, a per-clinic `CUSTOM_REST`, …).

When the federation runtime lands (M3), a clinic's sources are persisted as `sources(id, clinic_id, kind, connection_params JSONB, …)` and the registry is built by reading those rows, switching on `kind` to instantiate the right adapter wired with the parsed params. Adding a *new instance of a known kind* is then config-only (insert a row); adding a *new kind* (protocol/CRM) is a code change (new adapter + enum value + params codec) — `kind` is a closed, `when`-able discriminator, not open config. **M2 builds none of this**: the registry is constructed in-process with the single `INTERNAL` source, and `DataSourceRegistry`'s aggregator method shapes (`listAllDoctors`, booking source-routing) are the seam the M3 multi-source federation extends.

#### Scheduling time model (FR-YDANPW schema, FR-W03362 scheduler)

The internal scheduler turns recurring availability into bookable `Slot`s (`start`/`end` are `Instant`, UTC). Three conventions make that conversion unambiguous:

1. **`doctor_weekly_hours.start_time` / `end_time` are LOCAL wall-clock**, not UTC — the literal time a clinic admin types ("10:00"). They carry no zone of their own.
2. **The anchoring zone is the clinic's**, stored as an **IANA zone id** in `clinics.config.preferences.timezone` (e.g. `'Asia/Tbilisi'`), default `'UTC'`. Clinic-level (a clinic is in one place; all its doctors share local time) — not a per-doctor field in M2. IANA (not a fixed `+04:00` offset) so it stays correct across DST for any future clinic that observes it.
3. **`day_of_week` (0=Sun .. 6=Sat) is evaluated in that clinic-local zone**, matching Postgres `EXTRACT(DOW)`.

So `InternalScheduler.availability(doctor, window)` resolves each `(date, weekly_hours row)` as *local wall-clock + clinic IANA zone → `ZonedDateTime` → `Instant`*, subtracts `doctor_time_off` + confirmed bookings, then tiles the remainder into `slot_minutes` chunks (trailing remainder shorter than `slot_minutes` is dropped). Worked example — Dr. X, Tue+Thu 10:00–18:00, clinic zone `Asia/Tbilisi` (UTC+4): two `doctor_weekly_hours` rows `(dow=2,'10:00','18:00')` + `(dow=4,'10:00','18:00')`; for Tue 2026-07-07 the window resolves to `[06:00Z, 14:00Z)`, tiled by a 30-min `slot_minutes` into 16 slots. `slot_minutes` is a single per-doctor duration (no per-service durations in M2).

**Minimum booking lead time (FR-W03362 AC.13).** Auto-assign must not propose a slot the patient cannot realistically reach, so `BookingService.findFirstAvailable` (and the decline re-propose) lower-bound the availability window at `now() + min_lead` rather than `now()`. `min_lead` is `clinics.config.preferences.min_lead_hours` hours — per-clinic, **default 24** (resolved at the `CoreApp` wiring layer alongside the clinic IANA zone). `min_lead_hours: 0` re-enables same-day/near-now booking (the `same_day` reminder path applies); the 24h default means "tomorrow or later." The `BookingService` constructor takes a `minLead: Duration` defaulting to `ZERO` (the pure-unit default proposes the literal earliest slot); the 24h product default lives in the config resolver, not the class.

### 3.5 `LlmRouterClient` port

```kotlin
interface LlmRouterClient {
    suspend fun classifyIntent(
        userMessage: String,
        language: String,
        availableIntents: Set<Intent>,
    ): IntentClassification

    suspend fun mapFreeFormToOption(
        userMessage: String,
        language: String,
        node: QuestionNode,
    ): OptionMapping
}

enum class Intent { SYMPTOM, BOOKING, FAQ, FALLBACK }
data class IntentClassification(val intent: Intent, val confidence: Double, val reason: String?)
data class OptionMapping(val pickedOptionValue: String?, val confidence: Double, val clarifyingQuestion: String?)
```

M2 default impl = `OpenAiLlmRouterClient` — uses OpenAI's chat completions API with structured outputs (JSON schema response) so the model literally cannot return an option_value outside the node's option set. Falls back to button-only UI if the OpenAI call errors or returns low-confidence (< 0.6).

### 3.6 HL7v2 message profiles (deferred to M3)

Not in M2 — booking is internal (§ 3.4). The HL7v2 wire profiles (SIU^S12 booking, ADT^A04 patient registration, AA/AE/AR ACK handling) land in M3 with the `Hl7v2Provider` implementation under `source/hl7v2/`; the segment-level design lives in FR-TNWP9R (moved to M3). These names are HL7-specific by definition and belong only in that package + the wire codec.

## 4. Key Patterns

### 4.1 State Management

- **Bot sessions** — `SessionRepo` is the system-of-record. The bot module is stateless across restarts; every incoming update is posted to `/bot/ingress`, the backend looks up or creates the session by `(platform, platform_user_id, clinic_id)`, and applies one walker step. Bot module's `SessionManager` (from M1 scaffold) is only an in-memory cache to avoid one backend round-trip per LRU-eviction; it can be evicted at any time without correctness loss.
- **Symptomizer walker** — pure function over `(algorithm, current_node_id, picked_option) → next_node_id_or_conclusion`. The walker is the single decider of which node is next.
- **LLM validation** — every LLM output passes through `RouterValidator.validate(intent_or_mapping, allowed_set)` before being applied. Rejection triggers re-prompt or button-only fallback.

### 4.2 Error Handling

- Backend: `plugins/StatusPages` catches every `Throwable`, logs at ERROR with `clinic_id` + `session_id` (when available), responds `500 {"error":"internal_error", "trace_id": "..."}`. Per-route validation responds 4xx directly with structured error bodies.
- Bot: adapters translate platform errors into a small `ApiErrorType` enum. Handlers never throw to the platform — they always reply with a user-facing message (looked up via the i18n catalog).
- Booking: a `BookingProvider` surfaces failures as source-agnostic typed exceptions — `SlotTakenException` (the internal exclusion constraint rejected an overlap → re-run first-available and re-propose) and `SourceUnavailableException` / `BookingFailedException` (a source — internal or external — could not complete; `BookingService` returns an i18n-keyed "booking temporarily unavailable, try again later"). Provider-specific failures (e.g. an HL7 AE/AR ACK) are mapped to these neutral types *inside* the provider impl — the service layer never sees an HL7 type.
- LLM unavailable: bot replies fall back to button-only UI (inline-keyboard with all current-node option labels in the user's language). No degraded LLM intent → user is asked to pick a goal explicitly.

### 4.3 i18n Catalog (frontend-owned — FR-VWRNDR)

- **Each frontend owns its own catalog; `core` holds zero presentation strings and zero localization keys.** There are two frontends: `messaging` (bot view layer) and `admin-panel` (the FR-XKH348 SPA). Both consume `core`'s semantic DTOs and localize chrome themselves. The bot catalog lives 100% in `messaging`; The former core `MessageCatalog` and the interim messaging `OutgoingMessageCatalog` (W03362 Variant A) are **unified into one** `messaging/i18n/MessageCatalog`.
- One YAML file per language under `messaging/src/main/resources/i18n/<lang>.yaml`, loaded at startup into an immutable `Map<String, Map<String, String>>` keyed by `lang → string_key → text`. It is **one disjoint key set** spanning both paths: ingress chrome + reminder/feedback/booking-outgoing. `MessageCatalog.get(key, lang, params?)` interpolates `{name}` placeholders; missing key → returns the key as a visible marker.
- All chrome lookups happen in `messaging` (`IngressRenderer` for the ingress path, `BotInternalOutgoingRoutes` for the reminder/feedback path). **Urgency icons + datetime formatting are view artifacts**, also owned here (`SlotFormatter`).
- **Algorithm-tree text is the exception**: it is `core`-owned domain data, localized **by `core`** to the session language (`localize()` + `allowed_languages` fallback) and shipped as concrete strings; `messaging` displays it verbatim and never looks tree text up in its catalog.
- Guard tests: `core`'s `CoreHasNoChromeTest` asserts zero catalog usage / no i18n bundles / no localization-key constants / no chrome glyphs; `core`'s `NoHardcodedLiteralsTest` sweeps for hardcoded Cyrillic chrome literals; `messaging`'s `BundleParityTest` (ru↔kk over the full catalog) + `OutcomeKeyCoverageTest` (every outcome variant + every `ReminderKind` → a backed key) guard the relocated catalog.
- **`admin-panel` SPA (FR-XKH348)** owns its own RU + KK catalog in `admin-panel/src/i18n/` (a flat key→string map, `ru` typed as the source key set so the compiler rejects drift). Its `i18n parity` Vitest spec mirrors `messaging`'s `BundleParityTest`. Semantic codes (urgency / clinic-action / specialist) are localized via `tCode(prefix, code)` with raw-code fallback for open-ended algorithm-authored specialist codes; algorithm/LLM text from the API is rendered verbatim.

### 4.4 Multi-tenancy enforcement

- Every repo function takes `clinic_id: Long` as first parameter. There is no repo overload that omits it.
- Routes resolve `clinic_id` at the route boundary (`/api/v1/clinics/{slug}/...`) by looking up the slug → `clinic_id` and validating the caller's API key against the clinic's hashed key. The resolved `clinic_id` is passed down as an explicit function parameter, never via a `ThreadLocal` or coroutine `Context` value.
- Integration test: a clinic-A admin call to `/api/v1/clinics/clinic-b/...` returns 403, not 404 (don't leak existence).

### 4.5 Reminder scheduler

- A coroutine launched from `CoreApp.main` (on `Dispatchers.IO`) runs `ReminderScheduler.pollOnce()` every 60s.
- Each poll atomically claims a batch: `UPDATE reminders SET status='sent' WHERE id IN (SELECT id FROM reminders WHERE status='pending' AND due_at < NOW() + INTERVAL '60s' FOR UPDATE SKIP LOCKED LIMIT 50) RETURNING …`. The claim-and-mark in one statement gives exactly-once delivery across concurrent pollers.
- For each claimed row: build a **semantic** `ReminderDue { platform_user_id, language, kind, doctor_name, slot_at, clinic_zone }` payload (from `reminders.payload`, which stores those concrete fields — **no `catalog_key`**, FR-VWRNDR) and POST it to the **messaging** module's `POST /bot-internal/outgoing`. `messaging` maps the typed `kind` (`pre_visit_24h` / `same_day` / `post_visit_feedback`) to its **own** catalog key, formats `slot_at` in `clinic_zone`, and sends via `MessagingPort.sendText`; **core neither renders nor formats**. A body lacking a recognized `kind` (e.g. a legacy `catalog_key` payload) is rejected 422. A dispatch failure flips the row back to `failed` for the next poll. Auth: shared secret `BOT_INTERNAL_SHARED_SECRET` in the `X-Internal-Secret` header (distinct from the ingress secret).
- Single-process safe via `FOR UPDATE SKIP LOCKED`; multi-process safe by the same mechanism when the scheduler is later extracted. Survives restarts — past-due `pending` rows are picked up on the first poll.

### 4.6 Testing Strategy

See `specs/testing-spec.md`.

## 5. Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| Kotlin | 2.2.20 | Language; JVM toolchain 21 |
| Ktor | 3.0.3 | HTTP server (backend) — core, netty, content-negotiation, kotlinx-json, status-pages, call-logging, auth (M2 adds api-key) |
| kotlinx-serialization-json | 1.7.3 | JSON serialization |
| kotlinx-coroutines-core | 1.10.2 | Async / suspend |
| `org.postgresql:postgresql` | 42.7.4 | JDBC driver |
| `ca.uhn.hapi:hapi-base` + `:hapi-structures-v25` | 2.5.1 | HL7v2 message build + parse (codec layer; transport is custom). M2 adds. |
| `com.openai:openai-java` (or `com.aallam.openai:openai-client`) | latest stable | OpenAI Chat Completions API + structured outputs. M2 adds. |
| SLF4J | 2.0.9 | Logging API |
| Logback | 1.5.18 | Logging impl |
| `io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram` | 6.3.0 | Telegram bot library |
| OkHttp | 4.12.0 | HTTP client (WhatsApp Cloud API + backend↔bot HTTP + OpenAI HTTP) |
| JUnit Jupiter | 5.11.4 | Test runner |
| `kotlin-test-junit5` | (kotlin version) | Kotlin test assertions |
| `ktor-server-test-host` | 3.0.3 | In-process Ktor testing |
| **testcontainers-postgresql** | 1.20.4 | M2 adds — real Postgres in integration tests |
| Detekt | 1.23.8 | Lint |
| `dependency-analysis-gradle-plugin` | 2.7.0 | Detects unused / misplaced deps |
| shadow | 9.2.2 | Fat jar packaging |

## 6. Risks & Considerations

- **OpenAI API dependency** — M2 default LLM is OpenAI. Outage → bot falls back to button-only UI (UX degrades but flow works). Rate-limit → same fallback. Cost monitoring is M3+. Data-residency: OpenAI processes prompts in US — for KZ PDn compliance the production path must either switch to self-hosted Llama (per spec §14.5.2 low priority) or get OpenAI Enterprise EU/CIS residency. **Track as material risk for the KZ market launch.**
- **HL7v2 codec correctness (M3)** — deferred off the M2 critical path now that booking is internal. When the `Hl7v2Provider` lands, hand-rolled SIU^S12 + ADT^A04 construction risks a vendor profile mismatch. Mitigation: exhaustive round-trip tests + a reference public HL7v2 test server before the first vendor adapter ships.
- **External-source mirror staleness (M3)** — once an external `MedicalDataSource` exists, symptree's mirror-on-write `bookings` row can drift if an appointment is cancelled/moved *directly* in the vendor system (a reminder could fire for a dead booking). Reconciliation (inbound webhook or polling diff) is a later FR. Non-issue for the internal-only M2 pilot.
- **Admin-panel auth surface** — `/admin/login` is a new public credential endpoint. Risk: brute force / user enumeration. Mitigation (FR-MG7TSR): constant-time credential comparison + identical 401 shape/timing for unknown-email vs wrong-password; rate-limiting is a hardening follow-up. Severity: medium until rate-limiting lands.
- **First frontend toolchain** — `admin-panel/` introduces Node/Vite into a pure-Kotlin repo. Risk: CI/build drift; the SPA is not in the `./gradlew detekt test` gate. Mitigation: toolchain isolated under `admin-panel/`, verified via its own `npm test`; a dedicated CI FR wires it into the pipeline. Severity: low.
- **Algorithm-tree authoring overhead** — Multilingual trees are hand-authored JSON. 26 general + 11 male-specific trees from the legacy Firebase data need RU→KK translation. **M2 only ports ≥3 trees** (to be picked in `specs/plan/M2.md`); the rest land in M3 with the WhatsApp launch.
- **Multi-tenancy by convention, not by row-level security** — every repo function takes `clinic_id` explicitly; no DB-level enforcement. Risk: a future developer adds a repo function that omits the param. Mitigation: integration test sweeps every route with a cross-clinic call and asserts 403.
- **Reminder scheduler in-process** — works for M2 pilot volume (1–2 clinics). Risk: a long-running poll blocks request-serving Netty threads. Mitigation: scheduler runs on its own dispatcher (`Dispatchers.IO`); migrate to a separate process when measured contention demands.
- **Data residency vs OpenAI** — see first bullet. The architecture chosen here (port-abstracted LLM) doesn't preclude a Llama-self-hosted swap, but the M2 OpenAI default is a deployment-time risk for any KZ-residency deployment.
- **Telegram + WhatsApp Cloud API rate limits** — per-bot quotas (Telegram: ~30 msg/s/bot; WA Cloud: per-phone-number-id tier). Reminder bursts at the top-of-hour could exceed. Mitigation: scheduler batches and paces sends.
- **PII encryption key rotation** — `SYMPTREE_PII_KEY` rotates infrequently; rotation requires re-encrypting existing `sessions.history.user_text_encrypted` rows. M2 has no rotation tooling. Risk: medium when first rotation is needed (M3+ deployment hygiene task).
- **One bot per clinic scaling ceiling** — FR-W3WDWH design hosts one Telegram poller per clinic in the same `messaging` JVM. Scales linearly; with ~10 clinics the connection pool + thread budget should stay comfortable, but past ~50 clinics this design will exhaust resources. Risk: low for M2/M3 pilot; medium for M4+ scaling.
- **`/bot-internal/outgoing` shared-secret** — FR-4GCYJK uses a single shared secret (`BOT_INTERNAL_SHARED_SECRET`, distinct from the bot/backend ingress secret) between backend and bot module. Same-machine deploy in M2 makes this low-risk, but split-deploy topology in M3+ needs proper secret rotation. Risk: low for M2; medium when topology splits.

## Architecture Decision Records

| Decision | Options Considered | Choice | Rationale |
|----------|---------------------|--------|-----------|
| Backend framework | Ktor 3.x, Spring Boot, Micronaut | **Ktor 3.x** | Lightweight, Kotlin-native, matches MMP reference. No annotation magic. |
| Persistence | Raw JDBC, Exposed, jOOQ, Hibernate | **Raw JDBC** | Matches MMP. SQL stays grep-able. |
| Bot/backend transport | shared library, gRPC, HTTP/JSON | **HTTP/JSON** | Independent deploy/scale. Matches MMP `dressmibot` pattern. |
| Bot platform abstraction | per-platform handlers, MessagingPort | **MessagingPort** | One handler tree, N adapters. |
| JSON lib | Jackson, Gson, kotlinx-serialization | **kotlinx-serialization** | Idiomatic Kotlin; compile-time type safety. |
| LLM provider | direct OpenAI, direct Claude, port + default | **Port + OpenAI default** | Spec §14.5.2 keeps the door open for swap to Claude/Llama. Port shape locks the contract. |
| LLM safety | trust LLM output, validate against expert algorithm | **Validate every output** | The LLM is auxiliary (requirements.md § 2.3). Hallucinated option picks are rejected. |
| Algorithm tree i18n | sibling `text_ru`/`text_kk`, nested `text: {ru,kk}`, separate translation files | **Nested object on text** | Scales to N languages; one tree file per version; lookup is `text[lang]`. |
| Care-provider abstraction | extend `ClinicAdapter`, capability-flagged source, composition of providers | **Composition of capability providers** (`MedicalDataSource`) | A source binds an independent provider per capability, so HL7 (no directory) + REST CRM (directory) + internal coexist without `NotImplemented` stubs. |
| Booking ownership (M2) | delegate to HIS (HL7/operator), symptree as scheduler of record | **symptree as scheduler of record (internal source)** | Removes the 6–12-month HIS integration from the pilot critical path; HL7/CRM sources additive later. |
| Slot allocation (M2) | first-available, round-robin, least-busy | **first-available only** | MVP; `round_robin`/`least_busy` deferred to FR-2VQ7CA (M3) behind the `SlotStrategy` seam. |
| Booking UX | auto-assign earliest + confirm, slot menu, operator books | **auto-assign earliest + confirm** | Simplest patient flow (one decision); slot-menu is FR-2VQ7CA. |
| Admin panel stack | Ktor SSR+htmx, Vite SPA, Compose-for-Web | **Vite SPA over REST** | Richer UI, conventional, mirrors bot↔core decoupling; Node toolchain isolated. |
| Admin panel SPA framework (FR-XKH348) | React + TypeScript, Svelte + TypeScript | **React + TypeScript + Vite** | FR-XKH348 default; ecosystem/hiring depth. Either was AC-acceptable (ACs are framework-agnostic). Stack: React 19 + react-router + Vitest + Testing Library; no UI framework dependency (plain CSS). |
| Admin panel serving topology (FR-XKH348) | served by `core` static route, separate static host, CDN | **Static host (any) — `dist/` is plain static assets** | Keeps the runtime boundary clean (HTTP only, no `core` compile coupling). `npm run build` → `admin-panel/dist/`; a `core` static route is an additive option later. |
| Admin panel build integration (FR-XKH348) | standalone Node toolchain (CI-only), Gradle module via Exec tasks | **Gradle module via Exec tasks** (mirrors MMP `frontend`) | `include("admin-panel")` with a `base`-plugin `build.gradle.kts` that drives `npm ci` / `npm run build` / `npm test` as `Exec` tasks wired to the `assemble` / `check` lifecycle. The root build excludes it from the Kotlin/detekt/dep-analysis plugins. So `./gradlew build` builds the SPA and `./gradlew test` runs its Vitest suite — the panel is part of the project gate (no Kotlin/compile coupling; HTTP-only at runtime). Requires Node ≥ 20 + npm on the build host. |
| Admin panel read surface (FR-XKH348) | pure client of FR-MG7TSR, extend the admin REST surface | **Two additive read endpoints** | MG7TSR's session-detail carried no conclusion and exposed no specialist-code source, so AC-XKH348.3/.5 were infeasible as a pure client. Added `GET /api/v1/admin/sessions/{id}` → semantic `conclusion` block + `GET /api/v1/admin/specialist-codes`. Both emit **semantic DTOs only** (FR-VWRNDR): urgency/clinic-action/specialist as codes; algorithm-authored diagnosis/action/tests text localized by `core` to the session language; the SPA owns all chrome i18n. Supersedes XKH348's original "no new backend coupling" note. |
| Clinic-user token | opaque DB session, signed JWT (auth0 java-jwt), hand-rolled HS256 | **Hand-rolled HS256 bearer (FR-MG7TSR)** | Stateless, no session table; `clinic_id` is a claim, never read from the request path. A JWT-shaped token (`base64url(header).base64url(claims).base64url(HMAC-SHA256)`) over a kotlinx-serialized claims payload via `javax.crypto.Mac` — no JWT library, so no transitive Jackson pulled into the shadowJar against the project's kotlinx-only JSON stance. Constant-time signature compare; secret + TTL from `CoreConfig` (`ADMIN_TOKEN_SECRET` / `ADMIN_TOKEN_TTL_MINUTES`). |
| HL7v2 surface (M3) | full lifecycle, minimum viable, defer | **Deferred to M3** | Booking is internal in M2; SIU^S12 + ADT^A04 + ACK land with `Hl7v2Provider`. Plain MLLP rejected per § 2.5. |
| Session schema | normalized rows, JSONB blob, hybrid | **JSONB blob** | M2 read patterns are dominated by current-state lookups; analytics + retention scan tolerate JSONB ops. |
| 24h reminder transport | in-process scheduler, separate module, Quartz, external cron | **In-process coroutine scheduler** | Single deploy unit for pilot scale. Migrate when measured volume forces. |
| Encryption surface | Postgres TDE only, app-layer AES-256, hybrid | **App-layer AES-256-GCM on PII columns** | Postgres TDE not assumed available on every deployment target (Yandex Cloud Managed Postgres lacks per-column TDE). Keys live in env / secret store. |
