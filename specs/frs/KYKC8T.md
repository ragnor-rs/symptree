---
title: Direct specialty-based booking in the bot
milestone: M3
status: active
archived_at: null
id: fr_01KV8BRQVFY621Y13NB1KYKC8T
created_at: 2026-06-16T14:01:17Z
---

## Requirement

Make the bot's **"📅 Записаться к врачу"** welcome goal actually book an appointment, instead of showing the current "coming soon" stub. Today `appointment_booking` maps to `GoalPromptKind.BOOKING_COMING_SOON` (`BotIngressRoutes.kt`) → `bot.booking.coming_soon`; the W03362 auto-booking engine exists but is reachable only as the terminal outcome of a symptom assessment, because the symptom conclusion supplies the target specialist. A patient who already knows they want a doctor has no way to book.

Wire the goal to a **specialty picker** (specialty only — no symptom, no specific-doctor choice) whose options are the clinic's bookable specialties, then reuse the existing `BookingService.findFirstAvailable → pendingRepo.propose → ConfirmSlot → confirm` path. The booking attaches to the patient's existing active session (no schema change). This is the M3 follow-up to the M2 pilot, which intentionally shipped booking only via symptom triage.

References: FR-W03362 (the booking engine + `ConfirmSlot`/confirm/decline path + reminders this reuses), FR-W3WDWH (the welcome goal menu that offers `appointment_booking`), FR-VWRNDR (semantic outcome DTOs — messaging renders, core owns logic), FR-PWVTT4 (one active session per platform-user-clinic; reused as the booking's session), brainstorm 2026-06-16 (specialty-only picker; reuse no-slots message; link to active session).

## Acceptance Criteria

- AC-KYKC8T.1: Tapping the `appointment_booking` welcome goal no longer returns `BOOKING_COMING_SOON`. Core returns a new semantic outcome `IngressOutcomeDto.SpecialtyPicker` carrying the list of the clinic's **bookable specialist codes** — the distinct `specialist_codes` across the clinic's **active** doctors (sorted, deduped). No catalog keys / chrome strings cross the wire (FR-VWRNDR).
- AC-KYKC8T.2: `messaging` renders `SpecialtyPicker` as a vertical menu whose buttons are localized specialty labels (new `bot.specialist.<code>` catalog keys, RU + KK) with callback ids `specialty:<code>`. A specialist code with no catalog label falls back to the raw code (same pattern as the admin SPA's `tCode`), never a blank button.
- AC-KYKC8T.3: Tapping a `specialty:<code>` button runs the existing booking path: `BookingService.findFirstAvailable(clinicId, code, lookahead)` honoring the clinic's `min_lead_hours` window, then `pendingRepo.propose(clinicId, activeSessionId, slot, code)`, and returns the existing `ConfirmSlot` outcome (doctor + slot, confirm/decline buttons). The confirm/decline callbacks (`booking:confirm` / `booking:decline`) reuse the W03362 path unchanged (booking row, two reminders, `bot.booking.confirmed`).
- AC-KYKC8T.4: The proposed/created booking attaches to the patient's **existing active session** (`bookings.session_id` = the session row already created at `/start`) — no schema change. Reminders + feedback resolve the patient's chat id through that session exactly as for symptom-triggered bookings.
- AC-KYKC8T.5: When the chosen specialty has no available slot within the lookahead, the bot shows the existing `bot.booking.no_slots` message (no new string). When the clinic has **zero bookable specialties** (no active doctors / no specialist codes), tapping the booking goal shows the same `bot.booking.no_slots` (or contact-clinic) message rather than an empty picker.
- AC-KYKC8T.6: A `specialty:<code>` callback for a code the clinic cannot book (no active doctor with that code — e.g. a stale button) is handled gracefully: it resolves to the no-slots/contact-clinic message, never a 500 or a silent no-op.
- AC-KYKC8T.7: The booking conversation is token/session-scoped like the rest of ingress — `clinic_id` and the session come from the authenticated bot-ingress context, never from the callback payload. A patient can only ever book within their own clinic.

## Technical Design

Builds on FR-W03362 (booking engine + pending-booking + ConfirmSlot), FR-W3WDWH (goal menu), FR-VWRNDR (semantic DTOs).

**Core**
- **New outcome DTO** (`routes/IngressOutcomeDto.kt`): `data class SpecialtyPicker(val specialists: List<String>) : IngressOutcomeDto` (semantic codes only). `BotIngressRoutes.handleGoalSelection("appointment_booking")` returns it (replacing the `BOOKING_COMING_SOON` mapping) — building the list from a new `DoctorRepo.distinctActiveSpecialistCodes(clinicId)` (or reuse the existing active-doctor read + flatMap `specialist_codes`, deduped/sorted). Empty list ⇒ return the no-slots/contact outcome instead of an empty picker.
- **New callback** (`BotIngressRoutes`): `request.callbackData?.startsWith("specialty:")` → extract code → call the booking propose path. Factor the symptom path's `concludeOrPropose` booking half into a reusable `SessionService.proposeBookingForSpecialist(session, specialistCode)` (or a `BookingService` entry) that does `findFirstAvailable` + `pendingRepo.propose` and returns a `StepResult.ConfirmSlot` / `NoSlots`. The existing `renderStepResult` already maps those to `IngressOutcome.ConfirmSlot` / `NoSlots`; `booking:confirm` / `booking:decline` interception is already wired.
- **No migration**: `bookings.session_id` keeps pointing at the patient's active session; `pendingRepo.propose` already takes `(clinicId, sessionId, slot, specialist)`.

**Messaging**
- **New port outcome** (`port/IngressOutcome.kt`): `SpecialtyPicker(specialists: List<String>)` mirroring the core DTO; deserialized from the wire.
- **Render** (`IngressRenderer`): a `SendMenu` (VERTICAL) with `text = catalog.get("bot.welcome.booking.pick_specialty", lang)` and one `MenuOption(id = "specialty:$code", label = specialtyLabel(code, lang))`, where `specialtyLabel` reads `bot.specialist.<code>` from the catalog, falling back to the raw code.
- **Catalog** (`i18n/ru.yaml` + `kk.yaml`): add `bot.welcome.booking.pick_specialty` and `bot.specialist.<code>` for the pilot's specialist codes (therapist, neurologist, ent, infectionist, cardiologist, … — at least the dev-clinic set). KK + RU parity (mirrors the existing `MessageCatalogConsistencyTest`).

**Specialist list source:** the clinic's **active doctors'** `specialist_codes` (only bookable specialties), NOT the enabled-algorithm conclusion codes — so the picker never offers a specialty with no doctor.

## Testing

Framework: JUnit 5 + `ktor-server-test-host` (core); the messaging module's existing renderer/catalog tests.

**Core**
- `BotIngressRoutes` test: a `goal:appointment_booking` callback returns a `SpecialtyPicker` whose codes equal the clinic's distinct active-doctor specialist codes (AC.1); with no active doctors it returns the no-slots/contact outcome, not an empty picker (AC.5).
- A `specialty:<code>` callback with an available slot returns `ConfirmSlot` and creates a pending booking against the patient's active session (AC.3, AC.4); confirm then creates a booking + reminders (reuse W03362 coverage).
- A `specialty:<code>` with no slot → no-slots outcome (AC.5); a `specialty:<code>` for an unbookable/unknown code → graceful no-slots/contact, no 500 (AC.6).
- Session linkage: the created booking's `session_id` equals the active session id (AC.4).

**Messaging**
- `IngressRenderer` renders `SpecialtyPicker` as a vertical menu with `specialty:<code>` callbacks and localized labels; an unknown code falls back to the raw code (AC.2).
- Catalog parity: every new `bot.specialist.*` + `bot.welcome.booking.pick_specialty` key exists in RU + KK, non-blank (AC.2).

## Notes

- **No specific-doctor pick and no symptom** in this FR — specialty only (brainstorm decision). A doctor-targeted variant + symptom-free triage are out of scope (candidate for FR-2VQ7CA's slot-selection-strategy work).
- The reminder/feedback path is unchanged because the booking still hangs off a session row; this is exactly why "link to the active session" was chosen over a nullable-`session_id` migration.
- Specialty labels are bot-catalog-owned (messaging), distinct from the admin SPA's `specialist.*` bundle — same codes, separate catalogs (the two modules don't share i18n).
