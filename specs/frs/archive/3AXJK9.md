---
title: Booking flow + 24h reminder + post-visit feedback hook
milestone: M2
status: archived
archived_at: 2026-06-03T10:49:16Z
id: fr_01KT1YV4DCA34DZJP4QF3AXJK9
created_at: 2026-06-01T16:04:30Z
---

## Requirement

Glue layer that completes the triage happy path. Takes a symptomizer conclusion (from FR-PWVTT4) carrying `clinic_action == BOOK_APPOINTMENT`, queries available slots via `ClinicAdapter` (FR-TNWP9R), shows them to the user in their language (via FR-W3WDWH), books on selection, schedules a 24h-before reminder (via the in-process scheduler), and seeds a post-visit feedback prompt placeholder.

The bot also surfaces optional preliminary tests with prep notes (e.g., "blood test on empty stomach") on the conclusion message itself; that rendering is part of FR-W3WDWH (conclusion message); this FR owns the slot-pick → book → reminder → feedback chain.

References: technical-spec.md § 2.1 (`bookings` + `reminders` schemas), § 3.4 (`ClinicAdapter`), § 4.5 (reminder scheduler).

## Acceptance Criteria

- AC-3AXJK9.1: `bookings` + `reminders` tables exist per technical-spec.md § 2.1; migrations `database/04-migrate-reminders.sql` + `05-migrate-bookings.sql` apply cleanly.
- AC-3AXJK9.2: `BookingService.proposeSlots(sessionId, conclusion)` is called from the bot ingress handler when a conclusion has `clinic_action == BOOK_APPOINTMENT`. It (a) looks up the conclusion's `specialist` field against the clinic's specialist roster, (b) calls `ClinicAdapter.findSlots(specialistCode, now, now + booking_lookahead_days)` with the clinic's lookahead config, (c) returns up to 5 slots as a `SendMenu` outgoing action with the slot-start times rendered in clinic-local time + user's language.
- AC-3AXJK9.3: When the user picks a slot (callback-data carries the slot id), `BookingService.confirmBooking(sessionId, slotId)` is called. It loads the session + session's algorithm conclusion, calls `ClinicAdapter.bookAppointment(slot, patientRef, reason)`, inserts a `bookings` row with the returned `hl7_appointment_id`, sets the session's `status` to `concluded`, and returns a confirmation message (slot start + specialist name + clinic name + booking id, in user's language).
- AC-3AXJK9.4: On `bookAppointment` failure (HL7 NACK, network error), `confirmBooking` returns a user-facing failure message ("booking temporarily unavailable, try again later — your conversation is saved") and leaves the session active so the user can retry. The error is logged at WARN with `clinic_id` + `session_id`.
- AC-3AXJK9.5: When a booking is confirmed, a `reminders` row is inserted with `due_at = slot.start - 24h`, `kind = 'pre_visit_24h'`, and `payload` carrying the message-catalog key (`bot.reminder.pre_visit_24h`) + params (specialist name, slot time, prep notes). Idempotent: re-confirming the same booking does not insert a second reminder.
- AC-3AXJK9.6: `ReminderScheduler` runs as a coroutine launched from `CoreApp.main` on `Dispatchers.IO`. Polls every 60 seconds: `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 50` on `reminders WHERE status='pending' AND due_at < now() + interval '60s'`. For each row: render the catalog string in the session's language, POST to `/api/v1/clinics/{slug}/bot/outgoing` (new endpoint exposed by `core` for the bot module to receive backend-pushed messages), mark `status='sent'` on success or `status='failed'` on error.
- AC-3AXJK9.7: `POST /api/v1/clinics/{slug}/bot/outgoing` allows the backend's scheduler to push messages to the bot module. The bot module exposes a corresponding HTTP endpoint or long-poll; for M2 the bot module exposes `POST /bot-internal/outgoing` (auth via shared secret) that `core` calls. Topology: backend scheduler → bot module HTTP → Telegram. Single-direction; no callbacks from bot to scheduler.
- AC-3AXJK9.8: A `post_visit_feedback` reminder is also inserted on booking confirmation, with `due_at = slot.end + 2h`. M2 fires it but the reminder content is a single prompt ("How was your visit? Reply 1-5.") — the feedback collection + persistence is the only operational surface; analytics on the feedback ship in a later milestone.
- AC-3AXJK9.9: Two-instance test: when two `ReminderScheduler` coroutines poll concurrently (simulating an HA deploy in M3+), each row is picked exactly once thanks to `FOR UPDATE SKIP LOCKED`. Verified by a Testcontainers integration test.
- AC-3AXJK9.10: An end-to-end integration test (`BookingFlowIT`) walks: seed clinic + algorithm → seed user/session → drive symptomizer to a `BOOK_APPOINTMENT` conclusion → propose slots → confirm a slot → assert `bookings` row inserted + `reminders` row inserted + bot received the confirmation message. Runs against Testcontainers Postgres + `InMemoryClinicAdapter` + `FakeLlmRouterClient`.

## Technical Design

Builds on technical-spec.md § 2.1 + § 3.4 + § 4.5. Specifically:

- **Module layout:** `core/src/main/kotlin/com/symptree/core/booking/BookingService.kt`, `scheduler/ReminderScheduler.kt`, `db/{BookingRepo,ReminderRepo}.kt`.
- **Slot rendering:** times in clinic-local timezone (`clinics.config.preferences.timezone`), formatted per user's language via a localized formatter (`java.time.format.DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(...)`).
- **Slot callback-data shape:** Telegram callback_data is capped at 64 bytes. Use `bk:<session_id>:<slot_id_short>` where `slot_id_short` is the first 12 chars of the slot ID. The full slot ID is re-derived on backend by looking up the session's last-proposed slots cache (stored in `sessions.history` as the most recent turn).
- **Reminder payload:**
  ```json
  {
    "platform": "telegram",
    "platform_user_id": "...",
    "language": "ru",
    "catalog_key": "bot.reminder.pre_visit_24h",
    "params": {"specialist": "Невролог", "slot_time": "2026-06-15 10:00 (МСК)", "prep_notes": "..."}
  }
  ```
- **`POST /bot-internal/outgoing` (bot module):** receives the payload, calls `MessagingPort.sendText(...)` with the rendered string. Shared-secret auth via `BOT_INTERNAL_SHARED_SECRET` env on both sides.
- **Scheduler durability:** rows in `status='pending'` survive backend restarts. On startup, no special recovery — the poll naturally picks any past-due rows.
- **Idempotency on booking confirmation:** booking is keyed on `(session_id, slot.id)`; a second `confirmBooking` for the same pair returns the existing booking instead of double-booking.

## Testing

Per testing-spec.md. Specifically:

- Unit tests for `BookingService.proposeSlots` + `confirmBooking` — happy + every error branch.
- Integration tests for `BookingRepo` + `ReminderRepo` — Testcontainers, idempotency assertions.
- Concurrent-scheduler test per AC-3AXJK9.9 spins two `ReminderScheduler` coroutines against shared Postgres, inserts 100 due reminders, asserts each is sent exactly once.
- E2E test per AC-3AXJK9.10 lives in `src/test/kotlin/com/symptree/core/integration/BookingFlowIT.kt`.
- Recorded HL7v2 fixture replay: `BookingFlowHl7IT` (cross-FR with FR-TNWP9R) uses `Hl7v2ClinicAdapter` and asserts the SIU^S12 segment content for a known slot.
- Localized-time-format test: render slot start "2026-06-15 10:00" in RU + KK + (TR for M3 forward-compat), assert the format matches the locale's medium-style convention.

## Notes

- **ARCHIVED 2026-06-03 — split into two FRs by explicit scope decision.** With manual operator booking MVP-mandatory and load-balancing across doctors needed, this FR's automated-booking scope was too ambitious for M2 and missing the operator-handoff path. Split:
  - **M2 → FR-4GCYJK** ("Operator-assisted booking + reminders") covers manual-operator handoff, `booking_requests` admin REST, the reminder infrastructure (ACs .1, .5, .6, .7, .8, .9, .10 of this FR), and the post-visit feedback hook.
  - **M3 → FR-2VQ7CA** ("Automated booking + doctor identity + slot-selection strategy") covers the automated `BookingService.proposeSlots`/`confirmBooking` (ACs .2, .3, .4 of this FR), plus the doctor-entity model and `clinics.config.booking.slot_strategy` config (first/random/least-busy) that emerged during M2.
- The "post-visit feedback collection + analytics" surface is the smallest possible in M2 — fire the reminder, accept the reply, store in `sessions.history`. Rich analytics (NPS aggregation, per-clinic dashboards) ship in a later milestone.
- ICD code intake (clinic sends back ORU^R01 after visit) → dispensary triggers → M3+ (Dispensary module).
- Cancellation/reschedule flows (SIU^S15 / S13) → M3+.
- The scheduler's poll interval (60s) and batch size (50) are starting values; tune after pilot volume is measured.
- The `/bot-internal/outgoing` endpoint deliberately uses a separate shared secret (not the clinic-admin API key) since it's an internal trust relationship between backend and bot modules. Same machine usually; secret protects against accidental external exposure.
