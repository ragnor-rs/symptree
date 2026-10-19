# Testing Specification

## 1. Test Framework

- **Runner:** JUnit Jupiter 5.11.4 (`org.junit.jupiter:junit-jupiter`)
- **Assertions:** `kotlin-test-junit5` (`kotlin.test.*` — `assertEquals`, `assertTrue`, etc.)
- **Ktor in-process testing:** `io.ktor.server.testing.testApplication { ... }` with the in-process test client
- **Integration testing (M2 adds):** `org.testcontainers:postgresql:1.20.4` for real Postgres in integration tests. JDBC URL injected via `@DynamicPropertySource`-equivalent (Ktor: pass into the application module config).
- **Mocking:** prefer real objects + hand-rolled fakes; MockK is the default if mocking becomes necessary (only at unit-test boundaries to LLM / HTTP / Telegram client).
- **Coverage:** not measured yet. Add Kover (`org.jetbrains.kotlinx:kover`) when coverage targets get teeth — deferred.

## 2. Test Structure

**Layout: `tests/-mirror` (Gradle convention)** — Kotlin/Gradle places tests in `src/test/kotlin/<same-package-as-src>/`. This is not co-located; it's the standard Gradle convention. `/gate-check` probe #20 reads this declaration from `CLAUDE.md` § Testing Conventions.

```
core/
├── src/main/kotlin/com/symptree/core/routes/HealthRoutes.kt
├── src/test/kotlin/com/symptree/core/HealthRoutesTest.kt           # unit / Ktor-test-host
├── src/test/kotlin/com/symptree/core/integration/BookingFlowIT.kt  # integration (Testcontainers)
└── src/test/resources/
    ├── algorithms/headache-v2.json                                  # fixture trees
    ├── hl7/SIU_S12_sample.txt                                       # recorded reference messages
    └── telegram/update_*.json                                       # Telegram update fixtures
```

Integration tests are tagged via filename suffix (`*IT.kt`) so the Gradle gate can split fast unit tests from slower IT runs once we need to (M2 keeps both in `./gradlew test`; split in M3+ if it gets slow).

## 3. Conventions

### Naming

- **Files:** `<ClassName>Test.kt` for unit; `<FeatureName>IT.kt` for integration.
- **Test methods:** Backtick-quoted natural-language behavior descriptions (`fun \`booking flow rejects mismatched clinic_id\`()`). Reads cleanly in failure output.
- **Test classes:** mirror the package of the unit under test.

### What to Test

- **Happy path** for every public function / route.
- **Error cases** that the unit can produce (validation failures, 4xx/5xx responses, LLM rejections, HL7 NACKs).
- **Edge cases** that have caused bugs historically — keep the regression.
- **Symptomizer walker:** every node transition path in every shipped algorithm (data-driven test that walks the tree exhaustively).
- **LLM validator:** every rejection branch (unknown intent, option outside set, low confidence). Use fakes for the LLM client.
- **Multi-tenant safety:** every clinic-admin route called with mismatched clinic_id returns 403.
- **i18n catalog:** every key referenced from code is present in every shipped language's YAML; build fails if any user-facing literal Russian/Kazakh string exists in route/handler/adapter code (regex sweep over `.kt` files in test).
- **HL7v2 codec:** round-trip tests against recorded reference SIU^S12 + ADT^A04 messages — byte-equal after parse → build.
- **Reminder scheduler:** `FOR UPDATE SKIP LOCKED` claim semantics under concurrent pollers (integration test, two coroutines).

### What NOT to Test

- Implementation details (private methods, internal data structures).
- Third-party library internals (Ktor, OkHttp, HAPI HL7v2, OpenAI client, kotlin-telegram-bot).
- Generated code (none in this project yet).
- `main()` entry points — they're glue; verify components individually.
- Real network calls (OpenAI, Telegram, real HIS) — use fakes / Testcontainers / recorded fixtures. Live-API tests live in a separate manual `e2e/` directory, never in `./gradlew test`.

## 4. Coverage Targets

Aspirational until Kover is wired. /setup defaults retained for M2:

| Layer                                          | Target | Minimum |
|------------------------------------------------|--------|---------|
| `routes/` (backend)                            | >=90%  | 80%     |
| `symptomizer/` (walker + Conclusion)           | >=90%  | 80%     |
| `llm/RouterValidator` (safety-critical)        | >=95%  | 85%     |
| `core/` (bot handlers, session manager)        | >=90%  | 80%     |
| `source/` (capability interfaces + registry)   | >=85%  | 70%     |
| `source/internal/InternalScheduler` (booking)  | >=90%  | 80%     |
| `source/hl7v2/` (HL7 provider + codec, M3)      | >=80%  | 70%     |
| `booking/` (BookingService, SlotStrategy)       | >=90%  | 80%     |
| `auth/` (clinic-user login + token)             | >=90%  | 80%     |
| `db/` (repos)                                  | >=70%  | 50%     |
| `scheduler/ReminderScheduler`                  | >=80%  | 70%     |
| `messaging/i18n/MessageCatalog` (FR-VWRNDR)    | >=90%  | 80%     |
| `messaging/core/IngressRenderer` (FR-VWRNDR)   | >=90%  | 80%     |
| `messaging/adapter/`                        | >=70%  | 50%     |
| `messaging/client/CoreClient`            | >=70%  | 50%     |
| Overall                                        | >=75%  | 65%     |

The two safety-critical surfaces — `symptomizer/Walker` (medical decision tree) and `llm/RouterValidator` (rejects hallucinated LLM picks) — should be tracked higher than the rest as voluntary discipline even before Kover is wired.

## 5. Test Data

### 5.1 Postgres — Testcontainers

Integration tests use `org.testcontainers:postgresql:1.20.4` to spin a real Postgres 16 container per test class (or shared across the IT suite when warm-up cost dominates — configure in `src/test/kotlin/com/symptree/core/integration/PostgresTestSupport.kt`). Migrations applied at container start via Flyway (added as a runtime dep in M2 if not already) or by directly running the `database/*.sql` files.

### 5.2 Fixture helpers

A single `src/test/kotlin/com/symptree/core/Fixtures.kt` (per module) exposes builder functions for the canonical test entities:

```kotlin
fun fixtureClinic(slug: String = "test-clinic", defaultLanguage: String = "ru", allowedLanguages: List<String> = listOf("ru", "kk")): Clinic
fun fixtureAlgorithm(key: String = "symptom_demo_adult", version: String = "2.1.0"): Algorithm
fun fixtureSession(clinicId: Long, platform: Platform = Platform.TELEGRAM, language: String = "ru"): Session
fun fixturePlatformUser(language: String = "ru"): PlatformUser
fun fixtureSlot(specialistCode: String = "neurologist", startOffset: Duration = Duration.ofHours(24)): Slot
```

Each fixture function has sensible defaults so tests stay readable; override only the fields under test.

### 5.3 Recorded reference data

- **Algorithm fixtures:** `src/test/resources/algorithms/<key>-v<version>.json` — the multilingual JSON tree per § 2.2 of technical-spec. Loaded directly in tests; no clinic_algorithms wiring unless the test exercises tenancy. M2 ships ≥3 fixtures (the ≥3 ported algorithms — pick during plan.md).
- **HL7v2 fixtures:** `src/test/resources/hl7/` carries reference SIU^S12 + ADT^A04 + ACK messages copied from a public HL7v2 test corpus. Codec tests round-trip: parse → re-serialize → byte-equal.
- **Telegram update fixtures:** `src/test/resources/telegram/update_*.json` — JSON-shaped `Update` objects (text message, callback query, etc.) recorded for replay through the bot's `TelegramAdapter` test layer. Tests use the kotlin-telegram-bot library's update model to deserialize.

### 5.4 Time

All time-dependent code (reminder scheduler, retention job, slot finding) takes a `Clock` parameter. Tests inject `Clock.fixed(Instant.parse("..."), ZoneOffset.UTC)`. No `Instant.now()` calls in production code outside the `main()` wiring.

### 5.5 LLM fakes

`LlmRouterClient` fakes for unit tests:

```kotlin
class FakeLlmRouterClient(
    private val intentResponse: IntentClassification = IntentClassification(Intent.SYMPTOM, 0.95, null),
    private val mappingResponse: OptionMapping = OptionMapping(pickedOptionValue = "yes", confidence = 0.9, clarifyingQuestion = null),
) : LlmRouterClient { /* return canned responses */ }
```

Tests for `RouterValidator` use the fake with deliberately-bad inputs (intent outside set, picked option not in node's option set) to assert rejection.

End-to-end tests of the bot flow (Telegram update → backend → reply) use the fake LLM by default. Real-OpenAI tests live in `e2e/` and are NOT in `./gradlew test`.

### 5.6 Data-source fakes

The M2 booking path runs against the real `InternalScheduler` (it is the scheduler of record — its slot computation + exclusion constraint are exactly what's under test), so no booking fake is needed for the internal flow. Where a test needs a capability provider without a DB, an `InMemorySchedulingProvider` / `InMemoryBookingProvider` (a fixed in-memory slot calendar) implements the FR-YDANPW interfaces. The deferred `Hl7v2Provider` (M3) is tested via codec round-trip + a wire-format integration test; behavior tests of the booking *service* use the in-memory providers for speed.

### 5.7 Frontend tests (admin-panel, FR-XKH348)

The Vite + React + TS SPA is tested with **Vitest + Testing Library + jsdom**, run via `admin-panel` `npm test`. Because `admin-panel` is a Gradle module driving its npm scripts through `Exec` tasks (see technical-spec ADR), its Vitest suite **is** part of `./gradlew test` (and `./gradlew build` runs the production build) — so the panel is covered by the project gate. Coverage delivered:

- **`api/client.test.ts`** — `Authorization: Bearer` attach, no-header when logged out, 401 → `onUnauthorized` (the 401→login hook), server error-code surfacing, and that the login body never carries `clinic_id` (AC-XKH348.8).
- **`i18n/strings.test.ts`** — ru ↔ kk key-set parity + no-blank-value (mirrors `messaging`'s `BundleParityTest`).
- **`lib/schedule.test.ts`** — weekly-hours validation (inverted range, same-day overlap, split-shift OK) mirroring the server rule, grid ⇄ rows round-trip, the canonical **0=Sun..6=Sat** day convention (Sunday is 0, never 7), and `orderedDays` display ordering (Monday-first default; any first-day wraps to all seven once).
- **`pages/DoctorForm.test.tsx`** — specialist codes sourced from the API render + submit; existing edited codes pre-selected even if no longer offered.
- **`pages/ScheduleEditor.test.tsx`** — split-shift add/remove, client-side inverted-range block (no PUT), and server-422 message surfacing.
- **`pages/SessionDetailView.test.tsx`** — the Q→A walk + the semantic conclusion render (localized urgency badge + specialist, verbatim algorithm text, no-conclusion placeholder).

Backend additions for FR-XKH348 are tested in `core` under the Kotlin gate: `SessionConclusionRoutesTest` (the semantic conclusion block on session detail), `SpecialistCodesRoutesTest` (enabled-algorithm specialist codes, clinic isolation, auth), `ClinicSettingsRoutesTest` (first-day default Monday + timezone, configured override, auth), and the weekly-hours read cases in `ScheduleRoutesTest` (`GET …/weekly-hours` round-trips the persisted set incl. Sunday=0; cross-clinic 403). A manual smoke checklist lives in `admin-panel/README.md`.
