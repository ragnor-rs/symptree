# symptree

Multi-tenant symptom triage and appointment booking for clinics, in Kotlin.

A patient messages a clinic's Telegram bot in their own language and describes a
complaint in free text. An LLM maps that text onto a **deterministic decision
tree**, the tree produces a triage conclusion (urgency, recommended specialist,
optional tests with prep notes), and — when the conclusion calls for it — the bot
books the first available slot from the clinic's own doctor schedule and sends
reminders. Clinic staff manage doctors, schedules, sessions and bookings from a
web admin panel.

> **Not medical advice, and not a medical device.** symptree is triage-routing
> infrastructure. It makes no diagnosis of its own: every clinical decision comes
> from the decision tree an operator configures. This repository ships **one
> synthetic demo tree** whose content is placeholder text with no clinical
> meaning. Authoring real trees, having them clinically reviewed, and complying
> with local medical-device and personal-data law is the operator's
> responsibility. See [Clinical content](#clinical-content).

## Why it's built this way

The interesting constraint is that **an LLM is never allowed to make a clinical
decision.** It has exactly two jobs:

1. Classify intent — is this message a symptom, a booking request, a service
   question, or something for a human?
2. Map a free-text reply onto one of the current tree node's options.

Everything else is a deterministic walk over a versioned tree. Every LLM output
that would affect state is validated against the walker's view of legal next
states first, so a hallucinated option is rejected rather than followed. If the
LLM is unavailable the bot degrades to button-only mode and keeps working.

## Architecture

Three independently-deployable pieces that share no code — only HTTP contracts:

```
core/         Ktor REST API + Postgres (raw JDBC). Symptomizer engine, LLM
              router, tree walker, scheduler, booking, clinic-admin REST.
messaging/    Telegram + WhatsApp bots. Owns ALL user-facing chrome and i18n.
admin-panel/  Vite + React + TS SPA for clinic staff.
```

`messaging` has no compile-time dependency on `core`; it talks over HTTP via
`client/CoreClient.kt`. Adding a messaging platform means adding one adapter
behind `port/MessagingPort` — no handler changes. The backend emits **semantic
DTOs only** (urgency and specialist as codes); the frontend owns every
translated string.

Deliberate choices: raw JDBC, no ORM. No DI framework — dependencies are wired
by hand from `main()`. Config comes from environment variables only.

## Quickstart

Requirements: **JDK 21**, **Docker** (for Postgres and the test suite),
**Node 20+** (for the admin panel).

```bash
# 1. Config — dev-only placeholders, enough to boot the whole stack as-is.
#    `docker compose` reads .env automatically; `set -a` also exports it to
#    this shell for the jars below. Do this FIRST: the postgres image refuses
#    to initialize without a password.
cp .env.example .env
set -a; source .env; set +a

# 2. Postgres
docker compose up -d postgres

# 3. Backend — migrations apply at startup, the demo tree is seeded automatically.
#    Run from the repo root: migrations are read from ./database.
./gradlew :core:shadowJar && java -jar core/build/libs/core.jar

# 4. Create a clinic (returns a one-time admin password)
curl -X POST http://localhost:8080/api/v1/clinics \
  -H "X-API-Key: $PLATFORM_ADMIN_API_KEY" -H 'Content-Type: application/json' \
  -d '{"slug":"demo-clinic","name":"Demo Clinic","default_language":"ru",
       "allowed_languages":["ru","kk"],"admin_email":"admin@demo.test"}'

# 5. Admin panel
cd admin-panel && npm install && npm run dev     # http://localhost:5173
```

**A real `OPENAI_API_KEY` is required to walk the tree.** `.env.example` ships a
placeholder so core boots and the bot answers `/start`, but the first symptom
complaint fails: entering a journey needs the LLM to pick which tree the
complaint belongs to, and that call has no fallback — a bad key surfaces as
`500 internal_error`. The documented button-only degradation covers only the
*mid-tree* step (mapping a free-text reply onto the current node's options),
which is what keeps an in-progress session alive if the LLM drops out. Core
checks the variable is non-blank but never validates it, so there is no way to
boot without *something* there.

To run the bot, add a [BotFather](https://t.me/botfather) token to `.env`
(`TELEGRAM_BOT_TOKEN=`) and point it at the clinic:

```bash
set -a; source .env; set +a
export BACKEND_URL=http://localhost:8080
./gradlew :messaging:shadowJar && java -jar messaging/build/libs/messaging.jar
```

### Full stack in Docker

`docker-compose.yml` also defines `core`, `messaging` and `admin-panel`. Those
three images **copy prebuilt artifacts** rather than compiling inside the image,
so they must be built first — on a fresh clone `docker compose up -d` otherwise
fails with `"/core/build/libs/core.jar": not found`:

```bash
./gradlew :core:shadowJar :messaging:shadowJar :admin-panel:buildSpa
docker compose up -d --build
```

`core`, `postgres` and `admin-panel` (on
[http://localhost](http://localhost), where nginx reverse-proxies `/api` → core)
come up fine without a bot token. The `messaging` service needs a real
`TELEGRAM_BOT_TOKEN` in `.env`: with the variable empty it logs
`Telegram disabled … Bot module idle` and exits 0, leaving the other three
services running normally.

## What a session looks like

Walking the shipped demo tree in Russian. The questions are placeholder text —
a real deployment substitutes its own tree, and the chrome around it is identical:

```
you › /start
bot › Здравствуйте! Я помогу разобраться с симптомами и записаться к врачу.
bot › Чем я могу вам помочь?
      [🩺 Разобраться с симптомами] [📅 Записаться к врачу] [ℹ️ Узнать об услугах]

you › 🩺 Разобраться с симптомами
bot › Расскажите, пожалуйста, что вас беспокоит.

you › признак B у меня есть                    ← free text, mapped by the LLM
bot › ДЕМО, вопрос 1: признак A присутствует?
      [Да] [Нет]
you › Нет
bot › ДЕМО, вопрос 2: признак B присутствует?
      [Да] [Нет]
you › Да

bot › ⚠️ ДЕМО-исход 2: пример срочного исхода с анализами.
      ДЕМО: пример действия — предлагается запись к специалисту.
      Предварительные исследования: demo_test_a, demo_test_b
bot › Готовы записаться на приём?
      [Записаться сейчас]

you › Записаться сейчас
bot › Доктор Демо Невролог — 21.10 09:00 — подтвердить запись?
      [✅ Да, записаться] [🔄 Другое время]
you › ✅ Да, записаться
bot › Готово! Вы записаны к Демо Невролог на 21.10 09:00. Мы пришлём напоминание.
```

Switching language mid-session with `/language` re-renders the same tree in
Kazakh — the tree carries `{ru, kk}` text per node, and the bot chrome comes
from `messaging/src/main/resources/i18n/`. Urgency drives the outcome: an
`emergency` conclusion skips booking entirely and tells the patient to seek
immediate care; `self_care` and `pharmacy` conclusions offer no appointment.

## Clinical content

Decision trees are **content, not code**. The repo ships
`core/src/main/resources/algorithms/symptom_demo_adult-v1.0.0.json`: a synthetic
tree that exercises every `Urgency` and `ClinicAction` value so it doubles as the
reference example for the schema (documented in `specs/technical-spec.md` § 2.2).

To add your own, drop a JSON file in that directory and list it in
`AlgorithmSeedLoader.KNOWN_ALGORITHMS`. Trees are validated at ingestion: every
text-bearing field must carry every language the clinic allows, or the tree is
rejected. Clinics enable and disable trees individually.

## Development

```bash
./gradlew build            # everything, including the SPA
./gradlew detekt test      # the gate — must be green to merge
./gradlew :core:shadowJar  # fat jar
```

`./gradlew test` runs JUnit 5 for the JVM modules **and** Vitest for the SPA, so
it needs Node on the PATH. The integration tests use Testcontainers, so **Docker
must be running** — a cached `UP-TO-DATE` pass can otherwise hide a stopped
daemon. Verify properly with:

```bash
./gradlew clean detekt test --rerun-tasks --no-build-cache
```

Current suite: 422 tests (core 339, messaging 48, admin-panel 35).

See [CONTRIBUTING.md](CONTRIBUTING.md) for conventions and
[SECURITY.md](SECURITY.md) for vulnerability reporting.

## Known limitations

- The `/start` menu offers three goals but only **symptom consultation** is
  implemented. `appointment_booking` and `service_info` reply with a
  "coming soon" message.
- A complaint that matches no enabled tree returns a "no scenario for this
  complaint" message rather than falling back to a human handoff.
- Declining offered slots three times ends the flow by telling the patient to
  contact the clinic directly, instead of offering a date picker.
- The pre-visit reminder for a booking made less than 24h ahead fires almost
  immediately rather than being suppressed.
- The admin panel's weekly-hours grid is replace-on-save: the `PUT` replaces a
  doctor's full set rather than patching individual intervals.
- A clinic-admin login is seeded only when the clinic is created; there is no
  endpoint to provision an admin for an existing clinic.
- One bot process serves one clinic.
- No connection pool — connections come from `DriverManager` per call.
- The WhatsApp adapter is shape-locked but unimplemented (`TODO()`).
- PII encryption keys live in the deployment's secret store; there is no key
  rotation tooling.

## Roadmap

Tracked as FRs under `specs/frs/`:

| | |
|---|---|
| `KYKC8T` | Direct specialty-based booking in the bot |
| `6AXT7Z` | Editable clinic service-info shown in the bot |
| `FXJEVE` | Editable clinic settings in the admin panel |
| `A4ZDFW` | Per-clinic private algorithm catalog |
| `2VQ7CA` | Slot-selection strategies (`round_robin`, `least_busy`) + doctor identity |
| `TNWP9R` | HL7v2 adapter — federate an existing hospital information system |

Beyond those: the WhatsApp adapter, Turkish alongside it, and per-region data
residency.

## Tech stack

Kotlin 2.2.20 (JVM 21) · Ktor 3.0.3 + Netty · PostgreSQL via raw JDBC ·
kotlinx-serialization · kotlinx-coroutines · Gradle 9.2.1 · Detekt 1.23.8 ·
JUnit 5 + Testcontainers · Vite + React + TypeScript + Vitest

## License

[MIT](LICENSE) © 2026 Renat Sarymsakov
