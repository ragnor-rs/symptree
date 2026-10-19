---
title: Multi-tenant clinic model + clinic-admin REST
milestone: M2
status: archived
archived_at: 2026-06-02T11:05:28Z
id: fr_01KT1YV4DC1Q048TAY7NM3WYNS
created_at: 2026-06-01T16:04:30Z
---

## Requirement

Every persistent row and every request in the M2 surface is scoped to a clinic. Onboard a clinic, fetch its config, mutate its config, and toggle which symptomizer algorithms are enabled — all through a clinic-admin REST surface authenticated by a per-clinic static API key. Cross-tenant access returns 403 (not 404 — don't leak existence).

This FR ships the `clinics` table, the per-clinic config JSONB shape, the platform-admin + clinic-admin auth layer, and the REST routes that consume them. It is foundational for every other M2 FR — symptomizer sessions, algorithm catalog wiring, HL7v2 adapter target selection, booking flow, and reminders all key off `clinic_id`.

References: requirements.md § 2.1 (multi-tenancy invariant); technical-spec.md § 2.1 (`clinics` schema), § 3.1 (admin routes), § 4.4 (multi-tenancy enforcement pattern).

## Acceptance Criteria

- AC-M3WYNS.1: A `clinics` table exists in Postgres matching the schema in technical-spec.md § 2.1 (`id`, `slug` unique, `name`, `default_language`, `allowed_languages`, `config` JSONB, `is_active`, `created_at`, `updated_at`). Migration `database/01-migrate-clinics.sql` applies cleanly from an empty database.
- AC-M3WYNS.2: `POST /api/v1/clinics` accepts a JSON body `{slug, name, default_language, allowed_languages, config}`, validates the body, inserts a row, and returns `201 Created` with the new resource. The route is gated on the platform-admin API key; a missing or wrong key returns 401.
- AC-M3WYNS.3: `GET /api/v1/clinics/{slug}` returns the clinic's config when the caller's clinic-admin API key matches the clinic, or the platform-admin key is used. A clinic-admin key for clinic A calling `GET /api/v1/clinics/clinic-b` returns 403, not 404.
- AC-M3WYNS.4: `PUT /api/v1/clinics/{slug}/config` performs a deep-merge update of the `config` JSONB, gated on clinic-admin auth. The route returns `200 OK` with the merged config. The update is logged at INFO with `clinic_id` and a redacted diff (no api_key_hash in the log).
- AC-M3WYNS.5: `POST /api/v1/clinics/{slug}/algorithms/{algorithm_key}/enable` creates or updates a row in `clinic_algorithms` (`clinic_id`, `algorithm_id`, `enabled`). The algorithm must already exist in the `algorithms` table (FR-KT4697); otherwise the route returns 404. Toggling off is `POST /api/v1/clinics/{slug}/algorithms/{algorithm_key}/disable` (idempotent).
- AC-M3WYNS.6: Every repo function in `db/ClinicRepo.kt` takes `clinic_id: Long` as its first parameter (where applicable; insert/list-all on the platform-admin namespace are the exceptions and carry an explicit `// platform-admin only` comment). No repo function silently elides `clinic_id` from a query that has tenant-scoped rows.
- AC-M3WYNS.7: An integration test sweeps every clinic-admin route with a request whose API key belongs to a different clinic than the one named in the URL path and asserts every response is 403, none is 404 (existence leak), and none is 200.
- AC-M3WYNS.8: `CoreApp.main()` (production startup) constructs `PostgresDatabase` + `ClinicRepo`, applies every `database/*.sql` migration via `MigrationRunner` before binding the HTTP port, and mounts `clinicRoutes(repo, config.platformAdminApiKey)` alongside `healthRoutes()`. `MigrationRunner` is idempotent across restarts: it bootstraps a `schema_migrations(name, applied_at)` table and skips files it has already applied. Each migration applies in its own transaction; a failure rolls back and surfaces an `IllegalStateException` naming the offending file so startup fails loudly rather than leaving the schema half-applied. Without this wire-up the clinic routes would be tested but unreachable from a deployed jar.

## Technical Design

Builds on technical-spec.md § 2.1 + § 3.1 + § 4.4 — no divergence. Specifically:

- **API key storage:** clinic-admin API keys are stored in `clinics.config` as `api_key_hash` (Argon2id or bcrypt; pick one at first implementation and document). Plaintext key is returned exactly once on `POST /api/v1/clinics` creation and never persisted.
- **Slug uniqueness:** `slug` is the URL-safe public identifier (e.g., `clinic-alpha`, `mercy-clinic-almaty`). Enforced by Postgres UNIQUE; validation regex `^[a-z0-9-]{3,64}$` at the route boundary.
- **Config JSONB shape (M2):**
  ```json
  {
    "api_key_hash": "<hash>",
    "specialists": [{"code": "neurologist", "display_name": {"ru": "Невролог", "kk": "Невролог"}}, ...],
    "services": [{"code": "general_blood_test", "display_name": {...}, "preparation": {...}}, ...],
    "hl7": {"endpoint_url": "mllps://...", "facility": "...", "application": "..."},
    "telegram_bot_token_ref": "secret-store-key-or-direct-token",
    "preferences": {"booking_lookahead_days": 14, "reminder_24h_enabled": true}
  }
  ```
  Schema is documented here; runtime validation lives in `ClinicConfigValidator`.
- **Routing:** all clinic-admin routes mount under `/api/v1/clinics/{slug}/...`. A route plugin resolves `{slug}` → `clinic_id` once at the route boundary and stashes it in the call's request-scope attributes, then validates the API key. Sub-routes read `clinic_id` from the attribute.
- **Platform-admin auth:** a single env-injected key (`PLATFORM_ADMIN_API_KEY`) authorizes `POST /api/v1/clinics`. Multi-platform-admin user management deferred.

## Testing

Per testing-spec.md § 3 + § 5. Specifically:

- Unit test `ClinicConfigValidator` — happy + every validation-failure branch.
- Unit test `ClinicRepo` — Testcontainers Postgres, every public function, cross-clinic-isolation assertions (function called with wrong `clinic_id` returns empty / does not mutate).
- Route-level test (Ktor `testApplication`) for each of the 5 routes — happy + 401 (no key) + 403 (wrong clinic's key) + 4xx (validation).
- **Cross-tenant sweep test:** the test required by AC-M3WYNS.7 lives in `src/test/kotlin/com/symptree/core/integration/MultiTenancySweepIT.kt`.
- i18n key sweep: every user-facing string returned by these routes flows through `MessageCatalog`; the i18n catalog test (FR i18n in TG bot — covered by FR-W3WDWH) will assert this via reflection / source scan.

## Notes

- API keys are returned plaintext exactly once on creation. The clinic admin is responsible for storing it. There is no "show my key" endpoint; rotation is via re-POSTing the clinic (which invalidates the old hash). Define rotation policy explicitly in the first deploy runbook.
- **`PUT /api/v1/clinics/{slug}/config` strips `api_key_hash` from the incoming patch** before deep-merging it, and emits an INFO log noting the strip. This is the load-bearing security invariant that makes the "rotation only via re-POST" policy above enforceable — clinic admins must not be able to rotate (or clobber) the stored hash through the config endpoint. The strip is silent (200 OK, not 400) so legitimate config-bag PUTs that accidentally echo back the full config (read-modify-write style) still succeed.
- **`PLATFORM_ADMIN_API_KEY` is a hard requirement at startup.** `CoreConfig.fromEnv()` refuses to construct (and the app refuses to start) when the env var is unset or blank — without it, `POST /api/v1/clinics` would be permanently inaccessible and the platform would silently appear healthy. Test/dev wiring that bypasses `fromEnv()` may pass any non-blank value.
- The migration `database/01-migrate-clinics.sql` ships a **minimal `algorithms` shell** (just `id`, `algorithm_key`, `is_active`, `created_at`) alongside `clinics` and `clinic_algorithms`. FR-KT4697 will `ALTER` the `algorithms` table to add `version`, `metadata`, and `tree` columns when the algorithm-catalog work lands.
- `clinic_algorithms` table is shipped with this FR even though it's exercised by FR-KT4697 (algorithm catalog) — keeping the schema together avoids a circular FR dependency.
- Platform-admin web UI / multi-platform-admin RBAC deferred.
