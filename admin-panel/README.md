# symptree · clinic-admin panel

The clinic-admin web panel — a **Vite + React + TypeScript SPA** (FR-XKH348) over the
clinic-admin REST surface (FR-MG7TSR). It is decoupled from `core` at runtime exactly like
the bot module (**HTTP only, no compile coupling**), but it *is* a Gradle module: a
`base`-plugin `build.gradle.kts` drives `npm ci` / `npm run build` / `npm test` via `Exec`
tasks wired to the lifecycle, so **`./gradlew build` builds the SPA and `./gradlew test`
runs its Vitest suite** (it is part of the project gate). The commands below run the same
toolchain directly.

## Requirements

- Node ≥ 20, npm ≥ 10
- A running `core` backend (default `http://localhost:8080`) for live use

## Install

```bash
cd admin-panel
npm install
```

## Develop

```bash
npm run dev        # Vite dev server on http://localhost:5173
```

The dev server proxies `/api` → `http://localhost:8080` (see `vite.config.ts`), so the
fetch wrapper always uses same-origin relative URLs. Point a local `core` at `:8080`
(`java -jar core/build/libs/core.jar`) and log in with a seeded clinic-admin
(`POST /api/v1/clinics` with `admin_email` returns a one-time `temp_password` — FR-PRVSN2).

## Test

```bash
npm test           # vitest run (component + unit tests)
npm run test:watch
```

## Build

```bash
npm run build      # tsc --noEmit && vite build → dist/
npm run preview    # serve the production build locally
```

`dist/` is static assets servable by any static host (or a future `core` static route —
deployment topology is recorded in `specs/technical-spec.md`). The Gradle wiring
(`./gradlew :admin-panel:build`) runs `npm ci` → build → test, so CI that runs
`./gradlew build` covers the SPA too (requires Node ≥ 20 + npm on the build host).

## Separation of concerns

The backend emits **semantic DTOs only** (FR-VWRNDR): urgency / clinic-action / specialist
as codes, plus algorithm-authored text already localized to the session language. The SPA
owns **all chrome localization** (RU + KK bundles in `src/i18n/`) and renders algorithm/LLM
text verbatim. The SPA never sends a `clinic_id` — the server derives it from the bearer
token (AC-XKH348.8).

## Architecture

```
src/
├── api/         # ApiClient (token attach + 401→login) + wire types
├── auth/        # in-memory token + AuthProvider
├── i18n/        # ru/kk catalog + provider (compile-time + runtime parity)
├── components/  # Layout, UrgencyBadge, ConfirmDialog
├── lib/         # pure helpers: schedule validation, formatting
└── pages/       # Login, Doctors, ScheduleEditor, Sessions, SessionDetail, Bookings
```

## Known M2 limitations

- The weekly-hours grid is **replace-on-save** (the only weekly-hours API is a replacing
  `PUT`; there is no `GET`), so it opens empty each visit. A `GET weekly-hours` read
  endpoint is a candidate backend follow-up.

## Manual smoke checklist (before pilot)

Run against a local `core` with a seeded clinic + admin, in **both RU and KK**:

1. **Login** — wrong password → error; correct → lands on Doctors.
2. **Doctors** — create a doctor (specialist codes come from the API dropdown); edit it;
   toggle "show deleted"; soft-delete one and confirm it shows as deleted.
3. **Schedule** — add a split shift (two intervals on one day); enter an inverted range →
   client error; save a valid grid; add + remove a time-off window.
4. **Sessions** — open a concluded session → the Q→A walk + the conclusion (urgency badge,
   specialist, diagnosis, tests + prep) render; an un-concluded session shows the placeholder.
5. **Bookings** — list shows doctor/slot/status/feedback; cancel a confirmed booking with a
   reason → it leaves the confirmed set; re-cancel is rejected.
6. **Language** — toggle RU↔KK; the choice persists across reload; no Cyrillic chrome is
   hardcoded (all from the bundle).
```
