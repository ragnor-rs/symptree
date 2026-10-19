---
title: Clinic-user auth + clinic-admin panel REST
milestone: M2
status: archived
archived_at: 2026-06-17T14:33:06Z
id: fr_01KTVA849JKHATNPWQ74MG7TSR
created_at: 2026-06-11T12:24:26Z
---

## Requirement

The admin panel (FR-XKH348) needs a **human login** that the current per-clinic API-key scheme (FR-M3WYNS) doesn't provide. Introduce a `clinic_users` entity (email + bcrypt password, scoped to a clinic, with a role) and a token-based browser-auth flow, and expose the **clinic-admin panel REST surface** the SPA consumes: doctor + schedule CRUD (FR-YDANPW catalog), a symptomizer-results read API, and booking management.

The machine-facing per-clinic API key (FR-M3WYNS) is retained for integration callers; `clinic_users` auth is the human/browser boundary. Per-operator identity (which the booking layer FR-4GCYJK explicitly punted on) is established here so the deferred operator↔patient chat fast-follow has an actor to attribute messages to.

References: FR-M3WYNS (clinic model + per-clinic API key — the machine auth this complements); FR-YDANPW (doctor/schedule catalog this exposes); FR-W03362 (bookings this manages); requirements.md § 2.1 (multi-tenancy — every route validates `clinic_id` against the authenticated user's clinic), § 2.5 (AES-256 PII at rest — results API decrypts server-side), NFR-2 (152-FZ / KZ PDn — export/delete surface); brainstorm 2026-06-11 (Vite SPA + REST, `clinic_users` auth).

## Acceptance Criteria

- AC-MG7TSR.1: Migration `database/09-migrate-clinic-users.sql` creates `clinic_users`: `id`, `clinic_id` FK, `email` (`UNIQUE (clinic_id, email)`), `password_hash` (bcrypt), `display_name`, `role` (`admin | operator`), `active`, `created_at`, `updated_at`. Passwords are never stored or logged in plaintext.
- AC-MG7TSR.2: `POST /api/v1/admin/login` — body `{clinic_slug, email, password}`; on success returns a signed bearer token (JWT or opaque DB-session token) carrying `clinic_id` + `clinic_user_id` + `role` + expiry; 401 on bad credentials (same response shape + timing for unknown email vs wrong password — no user enumeration). Token TTL configurable via `CoreConfig`.
- AC-MG7TSR.3: An auth plugin validates the bearer token on every `/api/v1/admin/**` route and puts `clinic_id` + `role` in the call context. The `clinic_id` is taken from the **token**, never from a path/body param — a request can only ever act on its own clinic. Cross-clinic access returns 403 (never 404 or 200); a `MultiTenancySweepIT`-style test asserts this across every admin route.
- AC-MG7TSR.4: Doctor CRUD: `POST/GET/PATCH/DELETE /api/v1/admin/doctors` — create (auto-creates the singleton `doctor_identity` per AC-YDANPW.7), list (active + soft-deleted filter), update (`specialist_codes`, `slot_minutes`, `full_name`, `active`), delete = soft-delete (`deleted_at`). `admin` role required for writes; `operator` is read-only on doctors.
- AC-MG7TSR.5: Schedule CRUD: `PUT /api/v1/admin/doctors/{id}/weekly-hours` (replace the doctor's weekly-hours set — split shifts allowed) and `POST/GET/DELETE /api/v1/admin/doctors/{id}/time-off`. Validates `end_time > start_time` and `end_at > start_at` (422 on violation) and overlapping weekly-hours rows for the same weekday (422).
- AC-MG7TSR.6: Symptomizer-results read API: `GET /api/v1/admin/sessions?status=&from=&to=&cursor=` (cursor-paginated list — patient ref, language, status, conclusion summary, timestamp) and `GET /api/v1/admin/sessions/{id}` (full walked path + conclusion: urgency, specialist, tests, prep). PII fields (`platform_user_id`, `history` free-text) are **decrypted server-side** for the authenticated clinic only; clinic-isolation enforced.
- AC-MG7TSR.7: Booking management: `GET /api/v1/admin/bookings?status=&from=&to=` (list with doctor display name + slot + status + feedback) and `PATCH /api/v1/admin/bookings/{id}` to cancel (`{action:"cancel", reason}`) — cancel pushes `bot.booking.cancelled` to the patient via `/bot-internal/outgoing` and frees the slot (exclusion constraint releases). Cancel on an already-cancelled booking → 409.
- AC-MG7TSR.8: Data-subject endpoints (NFR-2 right-to-export/delete): `GET /api/v1/admin/sessions/{id}/export` returns the decrypted session history as JSON; `DELETE /api/v1/admin/sessions/{id}` triggers anonymization per requirements § 2.4 (not a hard row delete — PII fields anonymized, aggregate survives). `admin` role required.
- AC-MG7TSR.9: Every admin route returns structured error bodies (consistent with `plugins/StatusPages`); all user-facing strings (validation messages surfaced to the SPA) flow through the i18n catalog per requirements § 2.2 — no hardcoded literals (extends `NoHardcodedLiteralsTest` coverage to the new `routes/admin/` package).

## Technical Design

Builds on FR-M3WYNS (auth pattern + clinic model), FR-YDANPW (doctor catalog), FR-W03362 (bookings), technical-spec.md § 3.1 (HTTP surface), § 4.4 (multi-tenancy).

- **Module layout:** `routes/admin/{AdminAuthRoutes,DoctorRoutes,ScheduleRoutes,SessionReadRoutes,BookingAdminRoutes}.kt`, `auth/{ClinicUserAuth,TokenService}.kt`, `db/ClinicUserRepo.kt`, `model/ClinicUser.kt`. `crypto/PiiCipher` (FR-PWVTT4) is reused for decrypt on the results API.
- **Token choice:** opaque DB-session token or signed JWT — JWT preferred (stateless, no session table), HS256 with a secret from `CoreConfig`. `clinic_id` is a claim; the auth plugin never reads `clinic_id` from the request path. (ADR candidate for technical-spec.md.)
- **bcrypt:** the project already hashes the clinic API key with bcrypt (FR-M3WYNS `withoutApiKeyHash`); `clinic_users.password_hash` reuses the same library — no new dependency.
- **Role model:** two roles for MVP (`admin` = full config; `operator` = read results + manage bookings, no doctor edits). Finer-grained RBAC + per-operator audit defer.
- **Results-read decryption boundary:** decrypt happens in the route handler for the authenticated clinic only; the list endpoint returns a redacted summary (no full free-text) and the detail endpoint returns the decrypted walk — keeps bulk listing cheap and PII exposure intentional.

## Testing

Per testing-spec.md.

- `ClinicUserRepoIT` (Testcontainers) — CRUD, `UNIQUE (clinic_id, email)`, cross-clinic isolation, bcrypt round-trip never exposes plaintext.
- `AdminAuthRoutesTest` (Ktor `testApplication`) — login happy + 401 (bad password = unknown email, same shape/timing) + expired-token 401 + role-gate 403.
- `DoctorRoutesTest` / `ScheduleRoutesTest` — CRUD happy + 401/403/422 (inverted ranges, overlapping weekly hours) + soft-delete.
- `SessionReadRoutesTest` — list + detail decrypt for own clinic; cross-clinic 403; export/delete (anonymize) paths.
- `BookingAdminRoutesTest` — list, cancel (pushes outgoing), 409 on re-cancel.
- `MultiTenancySweepIT` — every `/api/v1/admin/**` route rejects a token from a different clinic with 403.

## Notes

- Operator↔patient chat (the deferred fast-follow) attaches to `clinic_users` for actor identity and to the messaging module's BOT↔OPERATOR handover; this FR establishes the identity but not the chat.
- Rate-limiting / brute-force protection on `/admin/login` is a hardening item (note in technical-spec risks) — minimally, constant-time credential comparison + the no-enumeration response shape land here.
- The per-clinic API key (FR-M3WYNS) and `clinic_users` tokens coexist: API key = machine/integration boundary, token = human/browser boundary.
