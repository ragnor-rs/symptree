# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — 2026-10-20

First public release.

### Added

- `LICENSE` (MIT), `README.md`, `CONTRIBUTING.md`, `SECURITY.md`.
- GitHub Actions CI running `./gradlew detekt test` on JDK 21 with Node 20 and
  Docker — the gate needs all three, since the aggregate `test` task covers the
  SPA's Vitest suite and the integration tests use Testcontainers.
- A test asserting the shipped demo tree covers every `Urgency` and
  `ClinicAction` value, so neither enum can gain a member with no shipped
  example.

### Changed

- The shipped algorithm catalog is a single **synthetic demo tree**
  (`symptom_demo_adult`). Decision trees are operator-supplied content: the
  project ships the engine, the schema, and one placeholder example whose text
  has no clinical meaning. `AlgorithmSeedLoader.KNOWN_ALGORITHMS` lists what is
  seeded.
- `POSTGRES_PASSWORD` has no default and must be set — `core` refuses to start
  without it rather than falling back to a weak known value.
- `STAGING_HOST` has no default and must be set for staging deploys; the deploy
  scripts carry no infrastructure addresses.

Test suite at release: 422 tests (core 339, messaging 48, admin-panel 35),
0 failures.

## [0.2.0] — 2026-06-17 — "Triage"

First patient-facing vertical slice: a multi-tenant clinic can run the
symptomizer end-to-end in a Telegram bot (RU + KK), auto-book the first
available internal slot, and manage doctors, schedules, sessions and bookings
through a web admin panel.

### Added

- Multi-tenant clinic model + clinic-admin REST surface (M3WYNS)
- Algorithm catalog + multilingual decision-tree walker (KT4697)
- Symptomizer session engine + LLM intent/answer router (PWVTT4)
- Telegram bot patient journey in Russian and Kazakh, with i18n catalog (W3WDWH)
- Medical data-source abstraction + canonical doctor catalog (`INTERNAL` source) (YDANPW)
- Internal scheduler + first-available auto-booking, with 24h pre-visit and post-visit feedback reminders (W03362)
- Clinic-user authentication + clinic-admin panel REST endpoints (MG7TSR)
- Seed a clinic-admin login on clinic creation (PRVSN2)
- Clinic-admin web panel: doctors, schedules, symptomizer sessions, bookings (XKH348)

### Changed

- View-layer i18n: backend emits semantic DTOs and the frontend owns all localization (ingress + reminders), superseding the earlier core-renders-catalog approach (VWRNDR)

Total test count at release: 386 tests, 0 failures, 0 errors.
