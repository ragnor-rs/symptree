---
title: Seed clinic-admin login on clinic creation
milestone: M2
status: archived
archived_at: 2026-06-17T14:33:06Z
id: fr_01KW7Q3M5P0R8S2TVADMPRVSN2
created_at: 2026-06-15T00:00:00Z
---

## Requirement

Close the bootstrap gap left open by FR-MG7TSR: `clinic_users` has an `insert`
repo + a login route, but **nothing creates the first row** — bootstrapping a
clinic-admin login requires hand-rolled SQL + a bcrypt hash today, so the
FR-XKH348 panel has no account to log into.

Project-owner decision (2026-06-15): **seed on clinic creation.** Extend the
existing `POST /api/v1/clinics` (FR-M3WYNS) so that, when an admin email is
supplied, it also provisions exactly one `admin` `clinic_users` row and returns
a one-time temporary password in the create response. Every clinic is then
loginable by construction; no separate bootstrap endpoint, no raw SQL.

Adding *further* users (more admins, operators) is explicitly **deferred** — a
later admin-managed `POST /api/v1/admin/clinic-users` is a fast-follow once the
pilot asks. This FR only guarantees the first admin exists.

References: FR-MG7TSR (clinic_users schema/repo + login route this enables);
FR-M3WYNS (the `POST /api/v1/clinics` endpoint extended here); FR-XKH348 (the
panel that consumes the seeded login); m2_progress "KNOWN PRODUCT GAP".

## Acceptance Criteria

- AC-PRVSN2.1: `CreateClinicRequest` gains an **optional** `admin_email`
  (`@SerialName("admin_email")`). When present, the create flow — after the
  `clinics` row is inserted — provisions one `clinic_users` row via
  `ClinicUserRepo.insert`: `role = "admin"`, `active = true`,
  `email = admin_email`, `display_name = admin_email`, `password_hash =
  ApiKeyHasher.hash(<generated temp password>)`.
- AC-PRVSN2.2: `CreateClinicResponse` gains an **optional** one-time
  `admin` block `{ email, temp_password }`, populated **only** when a user was
  seeded. The plaintext temp password is returned exactly once here and is
  never persisted in plaintext anywhere.
- AC-PRVSN2.3: The temp password is generated server-side with the existing
  `SecureRandom` instance, URL-safe base64, with entropy ≥ the clinic-admin API
  key (≥ 32 bytes). It is distinct from the clinic-admin `X-API-Key` value.
- AC-PRVSN2.4: **Backward compatible** — `admin_email` omitted ⇒ no user is
  seeded and the response omits the `admin` block. All existing FR-M3WYNS
  create callers/tests pass unchanged.
- AC-PRVSN2.5: An invalid `admin_email` (fails a basic shape check) ⇒ `400`
  with the existing `validation_failed` envelope, and **no clinic is created**
  (validation runs before the insert, alongside the existing
  `ClinicConfigValidator` pass).
- AC-PRVSN2.6: **End-to-end**: a clinic created with `admin_email` can
  immediately `POST /api/v1/admin/login` with `{ email, temp_password }` and
  receive a valid bearer token (integration test against a real DB).
- AC-PRVSN2.7: The temp password (and the seeded `password_hash`) never appear
  in any log line emitted by the create path.

## Technical Design

Builds on FR-M3WYNS `clinicRoutes` and FR-MG7TSR `ClinicUserRepo` / `ApiKeyHasher`.

- **Where:** `routes/ClinicRoutes.kt` `POST /api/v1/clinics` handler, after the
  `repo.insertClinic(...)` success branch and before `call.respond(Created, ...)`.
  Inject a `ClinicUserRepo` into `Route.clinicRoutes(...)` (wired from `CoreApp`).
- **Temp password:** reuse the existing `generateClinicAdminApiKey()` shape (its
  own `SecureRandom` + `Base64.getUrlEncoder().withoutPadding()`); generate a
  *second* value so the API key and the temp password are independent.
- **Hashing:** `ApiKeyHasher.hash(tempPassword)` — the same bcrypt used by the
  login verify path, so the round-trip in AC-PRVSN2.6 is guaranteed consistent.
- **Email validation:** a minimal non-empty `local@domain`-shape check (mirror
  whatever MG7TSR's login does for the field, or a single regex). Full RFC
  validation is out of scope.
- **Atomicity (accepted pilot risk):** `insertClinic` and `ClinicUserRepo.insert`
  open separate JDBC connections (no shared transaction, per the project's
  raw-JDBC no-pool pattern). The slug-UNIQUE check makes a duplicate-clinic
  failure surface *before* the user insert; a `clinic_users` insert failure on a
  freshly-created clinic is effectively impossible (no `UNIQUE(clinic_id,email)`
  collision possible on a brand-new clinic_id). If it nonetheless throws, the
  exception propagates (StatusPages → 500) and the just-created clinic has no
  admin — acceptable for the pilot; a single-transaction `createClinicWithAdmin`
  is noted as future hardening, **not** built here.
- **Response model:** add a nullable `admin: SeededAdmin?` (`@SerialName("admin")`,
  default null) to `CreateClinicResponse` with `email` + `temp_password`
  (`@SerialName("temp_password")`).

## Testing

Per testing-spec.md.

- `ClinicRoutesTest` (or the existing clinic-create test class): `admin_email`
  present ⇒ 201 with `admin.{email,temp_password}` populated; `admin_email`
  omitted ⇒ 201 with no `admin` block (backward-compat, AC-4); invalid
  `admin_email` ⇒ 400 `validation_failed` and no clinic row (AC-5).
- An integration test (real DB): create-with-admin → `POST /admin/login` with
  the returned temp password → 200 + bearer token (AC-6).
- A log-redaction assertion: the create path emits no line containing the temp
  password or the bcrypt hash (AC-7) — extend the existing redaction test if one
  exists, else assert via a captured log appender.

## Notes

- Adding further users (operators, extra admins) is a deferred fast-follow
  (`POST /api/v1/admin/clinic-users`, admin-gated) — out of scope here.
- Password reset / rotation for the seeded admin is also deferred; for the pilot
  the temp password is the working credential and rotation is manual.
- This is a small backend `feat` — it rides the normal `./gradlew detekt test`
  gate; no new dependencies.
