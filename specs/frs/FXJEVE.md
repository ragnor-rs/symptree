---
title: Editable clinic settings (admin panel)
milestone: M3
status: active
archived_at: null
id: fr_01KV7Z49E1NBP8W8MAAXFXJEVE
created_at: 2026-06-16T10:20:59Z
---

## Requirement

Let a **clinic admin** view and edit their clinic's operational preferences from the admin panel, and let an **operator** view them read-only. Today the panel can manage doctors, schedules, sessions, and bookings, but the clinic's own settings — timezone, booking lead time, schedule-grid first day, and bot languages — are only writable via raw SQL or at clinic creation. This FR adds a **Settings** section to the SPA plus the backend write path behind it.

The editable fields are exactly the M2-intended per-clinic customizability set (requirements § 2.2 + FR-A4ZDFW): `timezone`, `min_lead_hours`, `first_day_of_week` (all in `clinics.config.preferences`), and the `default_language` / `allowed_languages` columns. Edits are **admin-only** (operators read-only, mirroring FR-MG7TSR's role model); `clinic_id` is always token-derived, never sent by the client (FR-MG7TSR AC-MG7TSR.3, FR-XKH348 AC-XKH348.8). Wire stays semantic per FR-VWRNDR.

This is a fast-follow after M2 close. References: FR-XKH348 (the SPA + the existing `GET /api/v1/admin/clinic-settings` read this extends), FR-MG7TSR (admin/operator roles, token-derived tenancy, 422-via-StatusPages), FR-VWRNDR (semantic DTOs only), FR-W03362 (`min_lead_hours` semantics), FR-YDANPW (`first_day_of_week` display ordering, 0=Sun canonical), brainstorm 2026-06-16 (full-object PUT, safe-edit language guardrails, allow-and-warn timezone change).

## Acceptance Criteria

- AC-FXJEVE.1: `GET /api/v1/admin/clinic-settings` is extended to also return `min_lead_hours` (alongside the existing `timezone`, `first_day_of_week`, `default_language`, `allowed_languages`). The value is read from `config.preferences.min_lead_hours`, defaulting to the same default `core` resolves at booking time (24) when the key is absent. DTO stays semantic (no catalog keys / chrome strings) per FR-VWRNDR.
- AC-FXJEVE.2: A new `PUT /api/v1/admin/clinic-settings` accepts the **full settings object** (same shape `GET` returns: `timezone`, `min_lead_hours`, `first_day_of_week`, `default_language`, `allowed_languages`) and persists it. The request body carries **no** `clinic_id`; the target clinic is derived only from the verified bearer token. On success it responds 200 with the updated settings object (the same shape as `GET`, so the client can re-render from the response).
- AC-FXJEVE.3: The `PUT` is **admin-only**. A token whose role is `operator` (or any non-`admin` role) receives 403 and no write occurs. The role gate reuses FR-MG7TSR's `requireRole("admin")` pattern.
- AC-FXJEVE.4: Field validation — each violation responds **422** (via StatusPages, semantic error body, no write): (a) `timezone` not a valid IANA zone (`ZoneId.getAvailableZoneIds()`); (b) `min_lead_hours` not an integer in `0..168`; (c) `first_day_of_week` not an integer in `0..6`.
- AC-FXJEVE.5: Language **safe-edit** guardrails — each violation responds **422** (no write): (a) `allowed_languages` empty; (b) `default_language` not a member of `allowed_languages`; (c) any code in `allowed_languages` ∪ `{default_language}` outside the platform-supported set `{ru, kk}`. There is **no** coupling to in-flight sessions — existing sessions resolve via their own stored language regardless of the edit.
- AC-FXJEVE.6: The write is **atomic** — a single `UPDATE clinics SET default_language=?, allowed_languages=?, config=? WHERE id=?` (new `ClinicRepo.updateSettings`) sets the two language columns and writes `config` with the three preference keys (`timezone`, `min_lead_hours`, `first_day_of_week`) merged into `config.preferences` while preserving every other existing `config` key. No partial write is observable on a validation failure (validation precedes the statement).
- AC-FXJEVE.7: A `timezone` change is **accepted even when future bookings exist** — the backend never blocks or cascades on bookings. Already-booked rows keep their absolute UTC instants; only future availability computation and display re-anchor to the new zone. (The user-facing warning is the SPA's responsibility — AC-FXJEVE.11.)
- AC-FXJEVE.8: Cross-clinic isolation holds for the new route — an admin of clinic A can never read or write clinic B's settings (token-derived `clinic_id` only); a body-supplied `clinic_id` is ignored. **Note (impl):** `clinic-settings` is a no-`{id}` token-scoped route, so the id-route `AdminMultiTenancySweepIT` pattern (403-for-foreign-id) does not apply; isolation is proven instead by a dedicated test that writes with clinic A's token and asserts clinic B is untouched (and that a bogus body `clinic_id` pointing at B is ignored).
- AC-FXJEVE.9: `POST /api/v1/admin/login`'s response (`LoginResponse`) is extended to include the authenticated user's `role`, so the SPA can render role-appropriate UI. (The token remains the sole authority server-side; `role` in the response is advisory UI data only.)
- AC-FXJEVE.10: The SPA gains a **Settings** page + a nav entry, loading via `GET /api/v1/admin/clinic-settings` and saving via `PUT …/clinic-settings`. The client never sends `clinic_id`. The form has one save action; on a 422 it surfaces the server's semantic error mapped to a localized message.
- AC-FXJEVE.11: An **admin** sees an editable Settings form; an **operator** sees the same values **read-only** (no save affordance) — driven by the `role` from AC-FXJEVE.9. When the admin changes the timezone field, the page shows a one-line notice that existing bookings' displayed times may shift (display-only; the appointments themselves do not move).
- AC-FXJEVE.12: The Settings page is localized **RU + KK** via the existing frontend i18n bundle; no hardcoded user-facing Cyrillic literals in the new components (strings come from the bundle, consistent with FR-XKH348 AC-XKH348.7 and `NoHardcodedLiteralsTest` posture).

## Technical Design

Builds on FR-MG7TSR (admin REST + roles), FR-XKH348 (SPA + the `clinic-settings` read), FR-VWRNDR (semantic DTOs).

**Backend (`core`)**
- **DTOs** (`routes/admin/AdminRoutes.kt`): extend `ClinicSettingsResponse` with `@SerialName("min_lead_hours") val minLeadHours: Int`. Add `ClinicSettingsUpdateRequest` mirroring the response shape (timezone, min_lead_hours, first_day_of_week, default_language, allowed_languages) — **no `clinic_id` field**. `min_lead_hours` resolution in `handleClinicSettings` reads `preferences.min_lead_hours` (the same key `CoreApp` reads at booking time, default 24).
- **Route**: register `put("/clinic-settings")` inside the existing authenticated admin block; gate with `requireRole(claims, ADMIN_ROLE)` (the constant doctor writes already use). Handler `handlePutClinicSettings(deps, claims, request)`: validate → build merged config → call repo → re-respond the GET shape.
- **Validation** (pure, unit-testable helper, e.g. `validateClinicSettings(request): List<Violation>` or first-failure): IANA via `ZoneId.getAvailableZoneIds().contains(tz)`; `minLeadHours in 0..168`; `firstDayOfWeek in 0..6`; languages: non-empty, `default ∈ allowed`, `(allowed + default) ⊆ {"ru","kk"}`. Any violation → `respond(422, AdminErrorResponse("validation_failed"))` (reuse MG7TSR's 422 envelope + the schedule editor's `422` precedent), no repo call.
- **Config merge**: read `clinic.config` (a `JsonObject`), deep-merge the three keys into its `preferences` object (preserving other `preferences` keys and other top-level `config` keys), and pass the new `JsonObject` to the repo.
- **Repo** (`db/ClinicRepo.kt`): add `fun updateSettings(clinicId: Long, defaultLanguage: String, allowedLanguages: List<String>, newConfig: JsonObject)` running the single `UPDATE clinics SET default_language=?, allowed_languages=?, config=?::jsonb, updated_at=NOW() WHERE id=?` (one statement = atomic; `allowed_languages` via `conn.createArrayOf("text", …)` as `insertClinic` does). The existing `updateConfig` stays for callers that touch only `config`.
- **Login** (`handleLogin`): extend `LoginResponse` to `LoginResponse(token, role)` (additive field; `role = user.role`).
- **No migration**: the columns (`default_language`, `allowed_languages`) and `config` jsonb already exist (M3WYNS). No schema change.

**Frontend (`admin-panel`)**
- `api/types.ts`: add `minLeadHours` to `ClinicSettings`; add `role` to `LoginResponse`; add a `ClinicSettingsUpdate` type (same fields). `api/client.ts`: add `updateClinicSettings(body): Promise<ClinicSettings>` (`PUT /api/v1/admin/clinic-settings`).
- `auth/AuthContext.tsx`: capture `role` from the login response into context (in-memory, alongside the token) so pages can branch admin vs operator.
- `pages/SettingsPage.tsx`: load via `clinicSettings()`, render a form (timezone select/input, min-lead-hours number, first-day-of-week select, default-language + allowed-languages controls). Admin → editable + one Save; operator → read-only render. Timezone change shows the display-shift notice. 422 → localized error surfaced inline.
- `components/Layout.tsx`: add a `Settings` `NavLink`. `App.tsx`: add the `/settings` route.
- `i18n/strings.ts`: add RU + KK keys for all new chrome (labels, the timezone notice, validation-error mappings). No hardcoded Cyrillic in components.

**Day-of-week / language canonical:** `first_day_of_week` is 0=Sun..6=Sat (matches DB CHECK / `EXTRACT(DOW)` and FR-XKH348); platform-supported languages are `{ru, kk}` (the shipped algorithm + bundle set).

## Testing

Framework: JUnit 5 + `ktor-server-test-host` (core); Vitest + Testing Library (admin-panel). Conventions per testing-spec.md; admin-panel is a Gradle module in the gate (`./gradlew test` runs Vitest).

**Backend**
- Unit (`validateClinicSettings`): table-driven — valid case; invalid timezone; `min_lead_hours` of -1 and 169; `first_day_of_week` of -1 and 7; empty `allowed_languages`; `default_language` not in set; an unsupported code (e.g. `en`). Each asserts the expected violation (AC-FXJEVE.4, AC-FXJEVE.5).
- Route IT (`testApplication`): GET returns `min_lead_hours` incl. default-when-absent (AC-FXJEVE.1); admin PUT round-trips and the response equals a subsequent GET (AC-FXJEVE.2, AC-FXJEVE.6); operator PUT → 403, no mutation (AC-FXJEVE.3); each validation case → 422 with no mutation (AC-FXJEVE.4, AC-FXJEVE.5); PUT with a body-supplied `clinic_id` is ignored — token clinic is written (AC-FXJEVE.2/8); login response carries `role` (AC-FXJEVE.9).
- Config-merge IT (`ClinicRepoIT`): seed a clinic whose `config` has an unrelated `preferences` key + an unrelated top-level key; `updateSettings` preserves both while updating the three settings keys + both language columns; assert atomicity by reading back one row (AC-FXJEVE.6).
- Multi-tenancy: a dedicated isolation test (clinic A token writes; clinic B untouched; body `clinic_id` ignored) — the no-`{id}` route doesn't fit the id-route `AdminMultiTenancySweepIT` 403 sweep (AC-FXJEVE.8).
- Booking-coexistence IT: with a future booking present, a timezone-change PUT succeeds and the booking row's stored UTC instant is unchanged (AC-FXJEVE.7).

**Frontend**
- `SettingsPage` renders loaded values; admin sees editable controls + Save; operator sees read-only, no Save (AC-FXJEVE.10, AC-FXJEVE.11).
- Save calls `updateClinicSettings` with the full object and **no** `clinic_id` (AC-FXJEVE.10).
- Changing the timezone field shows the display-shift notice (AC-FXJEVE.11).
- A simulated 422 surfaces the localized error; RU + KK bundles cover every new key; no hardcoded Cyrillic (AC-FXJEVE.12).

## Notes

- **`LoginResponse` change is additive** — existing clients (none beyond this SPA) ignore the new `role` field; the token contract is unchanged. Server authorization remains token-only; `role` in the body is UI hinting, never trusted for access decisions (the 403 gate is server-side).
- **Out of scope:** provisioning additional clinic users (the separate `POST /api/v1/admin/clinic-users` fast-follow), editing clinic `name`/`slug`/`is_active`, and any per-service slot durations. Only the four preference groups above are editable.
- **Upper bound 168** for `min_lead_hours` (= 7 days) is a sanity ceiling, not a product rule — revisit if a pilot clinic needs a longer lead.
- The timezone control's allowed value set is the JVM's `ZoneId` catalog; the SPA may present a curated short list (e.g. the pilot's zones) while still accepting any valid IANA string the server validates.
