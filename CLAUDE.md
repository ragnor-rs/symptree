# symptree

Multi-tenant symptom-triage and appointment-booking platform for clinics: a Ktor REST backend over Postgres, a multi-platform bot frontend (Telegram + WhatsApp), and a clinic-admin SPA.

## Tech Stack

- **Language:** Kotlin 2.2.20 (JVM toolchain 21)
- **Build:** Gradle 9.2.1 (Kotlin DSL), multi-module
- **Backend framework:** Ktor 3.0.3 + Netty
- **Database:** PostgreSQL via raw JDBC (`org.postgresql:postgresql:42.7.4`) — no ORM, no connection pool
- **Bot libs:** `kotlin-telegram-bot:6.3.0` (Telegram); WhatsApp Cloud API over HTTP (`okhttp:4.12.0`)
- **Serialization:** `kotlinx-serialization-json:1.7.3`
- **Coroutines:** `kotlinx-coroutines-core:1.10.2`
- **Logging:** SLF4J 2.0.9 + Logback 1.5.18
- **Testing:** JUnit 5.11.4 + `kotlin-test-junit5` + `ktor-server-test-host`
- **Lint:** Detekt 1.23.8 (config: `detekt.yml`, relaxed style rules — see file)
- **Dep analysis:** `com.autonomousapps.dependency-analysis:2.7.0`
- **Fat jars:** `com.gradleup.shadow:9.2.2`

## Architecture

```
symptree/
├── core/             # Ktor REST API, talks to Postgres
│   └── src/main/kotlin/com/symptree/core/
│       ├── CoreApp.kt          # embeddedServer(Netty) entry
│       ├── config/                # CoreConfig (env-driven)
│       ├── plugins/               # Serialization, CallLogging, StatusPages
│       ├── routes/                # HTTP routes
│       └── db/                    # PostgresDatabase (raw JDBC)
└── messaging/             # Telegram + WhatsApp bots, talks to backend via HTTP
    └── src/main/kotlin/com/symptree/messaging/
        ├── MessagingMain.kt
        ├── config/                # MessagingConfig (env-driven)
        ├── port/                  # MessagingPort + DTOs (platform abstraction)
        ├── adapter/               # TelegramAdapter, WhatsAppAdapter
        ├── core/                  # State, UserSession, SessionManager
        └── client/                # CoreClient (HTTP)
```

The `messaging` module does NOT depend on `core`. They communicate over HTTP only. Each module produces its own `shadowJar` fat jar.

```
specs/
├── frs/
│   ├── <tracker-id>.md                  # one file per FR (tracker mode: <tracker-id>.md)
│   ├── VDTAF4.md                        # or short-ULID tail in mode: none
│   └── archive/                         # archived FRs (git mv'd here)
├── plan/
│   ├── M<N>.md                          # one file per milestone
│   └── archive/
├── requirements.md                      # cross-cutting only
├── technical-spec.md                    # architecture, ADRs, schemas
└── testing-spec.md                      # framework + conventions
```

## Key Commands

```bash
# Build everything
./gradlew build

# Build a specific module's fat jar
./gradlew :core:shadowJar
./gradlew :messaging:shadowJar

# Run tests (all modules)
./gradlew test

# Lint
./gradlew detekt

# Compile only (fast feedback)
./gradlew compileKotlin compileTestKotlin

# Run backend locally (after shadowJar)
java -jar core/build/libs/core.jar

# Run bot locally (after shadowJar)
java -jar messaging/build/libs/messaging.jar
```

**Gating rule:** Every feature must pass before merging:

```bash
./gradlew detekt test
```

(`./gradlew build` is the superset — it also runs `assemble` and `check`.)

## Workflows

**Bugfix:** `/report-issue → /debug → /implement → /tdd (auto) → /gate-check → /pr`
**Feature:** `/brainstorm → /spec-write → /implement → /tdd (auto) → /gate-check → /docs → /ship-milestone → /pr`
**Refactor:** `/implement → /simplify → /gate-check → /pr`

## Task Tracking

mode: none
branch_template: {type}/{slug}

`mode: none` means specs/requirements.md is the source of truth and FRs carry short-ULID stems (no external tracker). The `branch_template:` is consumed by `/implement`'s Phase 1 step 0.b″ branch proposal even under `mode: none` — the resolver short-circuits trackers on `mode: none`, but branch proposal still fires when the template is present.

Branch types align with the Conventional Commits subjects below (`feat | fix | docs | style | refactor | perf | test | build | ci | chore | revert`). Trunk-OK list narrows to `ci` only — every other type gets a derived branch (`feat/<slug>`, `fix/<slug>`, …). `<slug>` is the kebab-cased description (no FR-id prefix; FRs are referenced via the `Refs:` commit footer instead).

## Key Patterns

- **Backend ↔ bot decoupling:** the bot module has no compile-time dep on backend; talks to it exclusively over HTTP via `client/CoreClient.kt`.
- **Bot port/adapter:** all bot business logic uses `port/MessagingPort` only. Platform-specific code lives in `adapter/` (one adapter per platform). Adding a new platform = adding one adapter, no handler changes.
- **Raw JDBC for Postgres:** `db/PostgresDatabase.kt` opens connections via `DriverManager`. No ORM (Exposed / jOOQ / Hibernate). No connection pool yet — add HikariCP only if/when load requires it.
- **Config from env:** every module has a `config/<Module>Config.kt` data class with a `fromEnv()` factory. No `.properties` files, no config DSLs.
- **Kotlinx serialization for JSON:** prefer `@Serializable` data classes over Jackson / Gson.
- **Suspend functions for I/O:** all messaging-port methods are `suspend`. Backend route handlers run on Netty's coroutine dispatcher.

## Testing Conventions

- **Framework:** JUnit 5 (`org.junit.jupiter`) via `kotlin-test-junit5`
- **Ktor tests:** `io.ktor.server.testing.testApplication { ... }` with the in-process test client
- **Mocking:** none currently — prefer real objects + fakes. If mocking becomes necessary, MockK is the default.
- **File naming:** `<ClassName>Test.kt`
- **Layout:** `src/test/kotlin/<same-package-as-src>/` (Gradle convention, not co-located)
- **Coverage target:** TBD

## DO NOT

- Do not commit without user approval
- Do not add features not in the spec
- Do not introduce an ORM, JPA, or Spring — this project deliberately uses raw JDBC + Ktor
- Do not introduce DI frameworks (Koin / Dagger) without a spec — wire dependencies by hand from `main()`
- Do not edit `gradle/wrapper/gradle-wrapper.jar` or `gradlew*` by hand — update via `./gradlew wrapper --gradle-version=<x>`
- Do not commit secrets — Postgres password, bot tokens, WhatsApp access tokens are env-only

## Commit Convention

All commits follow [Conventional Commits v1.0.0](https://www.conventionalcommits.org/en/v1.0.0/). ⚠️ **Nothing enforces this.** `.git/hooks/` holds only the stock samples, so the convention rests on whoever writes the commit.

**Subject** — `<type>(<scope>): <description>`, ≤ 72 characters.

- **Type** — one of `feat | fix | docs | style | refactor | perf | test | build | ci | chore | revert`.
- **Scope** — encouraged; the primary touched area (e.g., `feat(backend): ...`, `feat(bot): ...`, `fix(db): ...`).
- **Breaking change** — append `!` (e.g., `feat!: drop legacy endpoint`).

**Tracker IDs** go in the footer:

```
Refs: <TICKET-ID>
```

(or the short-ULID tail of the FR file in `mode: none`). Multiple FRs in one commit get one `Refs:` line each.

## Docs

user_facing_mode: false
packages_mode: false
changelog_ci_owned: false

## Release Files

```yaml
files:
  - path: build.gradle.kts
    kind: regex
    pattern: 'version = "(?<version>\d+\.\d+\.\d+)(-SNAPSHOT)?"'
    replace: 'version = "{version}-SNAPSHOT"'
  - path: CHANGELOG.md
    kind: changelog
  - path: README.md
    kind: regex
    pattern: 'Latest: \*\*v(?<version>\d+\.\d+\.\d+) — '
    replace: 'Latest: **v{version} — '
    optional: true
```
