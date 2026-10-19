---
title: Operator-assisted booking + reminders
milestone: M2
status: archived
archived_at: 2026-06-11T12:24:26Z
id: fr_01KT6HM4VV5JJMYH1ZJY4GCYJK
created_at: 2026-06-03T10:49:16Z
---

## Requirement

> **SUPERSEDED 2026-06-11 by FR-W03362 (internal scheduler + auto-booking) under the M2 admin-panel re-scope.** The operator-handoff booking model (patient → `booking_requests` queue → operator REST confirm) is replaced by symptree-as-scheduler-of-record: the bot auto-assigns the first-available internal slot and the patient confirms. The reminder, post-visit-feedback, `/bot-internal/outgoing`, and `FOR UPDATE SKIP LOCKED` infrastructure specified here is **reused verbatim** by FR-W03362 (ACs .8–.11) — only the booking *trigger* changed. Retained for that design provenance.

Wire the M2 booking surface as **operator-assisted** rather than fully automated. When the symptomizer concludes with `clinic_action == BOOK_APPOINTMENT`, the bot tells the patient "your request has been sent; an operator will contact you" and creates a `booking_requests` row. The clinic operator polls a clinic-admin REST endpoint, confirms the request with a specific slot + doctor name (or cancels with reason), and the backend then schedules a 24h pre-visit reminder + a 2h post-visit feedback ping. Replaces the automated-booking scope of archived FR-3AXJK9.

Manual operator-handled booking was the MVP-mandatory shape; HIS/HL7v2 integration was out of MVP scope given the 6-12 month vendor approval cycles. Fully automated booking via HL7v2 (with doctor identity + slot-selection strategy) is deferred to M3 FR-2VQ7CA.

FR-TNWP9R (HL7v2 codec mock) still ships in M2 as M3 readiness — its `ClinicAdapter` port + `InMemoryClinicAdapter` are also used by this FR's integration tests, even though M2's user-facing flow does not consume `Hl7v2ClinicAdapter`.

References: technical-spec.md § 2.1 (`bookings` + `reminders` schemas — needs extension for `booking_requests`); archived FR-3AXJK9.

## Acceptance Criteria

- AC-4GCYJK.1: `booking_requests` + `bookings` + `reminders` tables exist; migrations `database/04-migrate-booking-requests.sql` + `05-migrate-bookings.sql` + `06-migrate-reminders.sql` apply cleanly. `booking_requests` schema: `id`, `session_id` (FK), `clinic_id` (FK), `specialist_code`, `conclusion_summary` (JSONB — i18n payload + tests + prep notes), `patient_contact_ref` (Telegram user id for M2; PII-light), `status` (enum: `pending` / `confirmed` / `cancelled` / `expired`), `created_at`, `confirmed_slot_at`, `confirmed_doctor_name`, `confirmed_by` (operator identifier), `operator_notes`, `cancelled_reason`, `cancelled_at`.
- AC-4GCYJK.2: `BookingHandoffService.createRequest(session, conclusion)` is called from `SessionService.applyStep` when a conclusion has `clinic_action == BOOK_APPOINTMENT`. It inserts a `booking_requests` row with `status = 'pending'`, flips the session's `status` to `concluded` (the symptomizer journey is done; the booking is asynchronous), and returns a `StepResult.HandoffPrompt` for the bot to render the "request sent" message via the i18n catalog key `bot.booking.request_sent`.
- AC-4GCYJK.3: Clinic-admin REST: `GET /api/v1/clinics/{slug}/booking-requests?status=pending&limit=N&cursor=...` returns pending requests with the conclusion summary + patient contact ref + creation timestamp. Clinic-API-key authed (same scheme as FR-M3WYNS). Cursor-paginated; default limit 50. Cross-clinic isolation enforced — Clinic-B never sees Clinic-A's requests.
- AC-4GCYJK.4: Clinic-admin REST: `PATCH /api/v1/clinics/{slug}/booking-requests/{id}` — operator confirms with body `{action: "confirm", confirmed_slot_at, confirmed_doctor_name, operator_notes?}` OR cancels with `{action: "cancel", cancelled_reason}`. Idempotent on `(id, action)` — re-confirming with the same payload returns the existing state, does not double-fire reminders. Confirm on already-cancelled row returns 409; cancel on already-confirmed returns 409.
- AC-4GCYJK.5: On `confirm` PATCH: backend (a) inserts a `bookings` row referencing the `booking_request`, (b) inserts a pre-visit reminder (`due_at = confirmed_slot_at - 24h`, `kind = 'pre_visit_24h'`, payload carries catalog key `bot.reminder.pre_visit_24h` + params: doctor name, slot time, prep notes from conclusion summary), (c) inserts a post-visit feedback reminder (`due_at = confirmed_slot_at + 2h`, `kind = 'post_visit_feedback'`, catalog key `bot.reminder.post_visit_feedback`), (d) pushes a confirmation message to the patient via `/bot-internal/outgoing` (catalog key `bot.booking.confirmed` with doctor name + slot time). Edge case: `confirmed_slot_at - 24h` in the past (same-day booking) → reminder is inserted with `due_at = now() + 1 minute` (still fires, but uses the `bot.reminder.same_day` catalog key instead).
- AC-4GCYJK.6: On `cancel` PATCH: backend pushes a cancellation message to the patient via `/bot-internal/outgoing` (catalog key `bot.booking.cancelled` with `cancelled_reason` interpolated). No reminders scheduled. Session remains in `concluded` state.
- AC-4GCYJK.7: `ReminderScheduler` runs as a coroutine launched from `CoreApp.main` on `Dispatchers.IO`. Polls every 60 seconds: `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 50` on `reminders WHERE status='pending' AND due_at < now() + interval '60s'`. For each row: render the catalog string in the session's language, POST to `/bot-internal/outgoing`, mark `status='sent'` on success or `status='failed'` on error. Scheduler durability: rows survive backend restarts; on startup the poll naturally picks any past-due rows.
- AC-4GCYJK.8: `POST /bot-internal/outgoing` on the messaging module: shared-secret authed via `BOT_INTERNAL_SHARED_SECRET` env (distinct from the bot/backend ingress secret); accepts `{platform_user_id, language, catalog_key, params}` and calls `MessagingPort.sendText(...)` after rendering the catalog string. 401 on wrong secret; 422 on missing catalog key; 200 on send success.
- AC-4GCYJK.9: Post-visit feedback collection: when the `post_visit_feedback` reminder lands, the bot sends "How was your visit? Reply 1-5." (catalog key). The user's next message is interpreted by `FeedbackHandler` (not the symptomizer LLM router) — if it parses as an integer 1-5 within 24h of the prompt, store as `bookings.feedback_score` + `bookings.feedback_text` (raw reply). Out-of-range or non-numeric replies get one re-prompt; after that the bot returns to the welcome menu. Analytics on feedback ship in a later milestone.
- AC-4GCYJK.10: Two-instance concurrency test: when two `ReminderScheduler` coroutines poll concurrently (simulating an M3+ HA deploy), each row is picked exactly once thanks to `FOR UPDATE SKIP LOCKED`. Testcontainers Postgres integration test inserts 100 due rows, spins two schedulers, asserts each row sent exactly once.
- AC-4GCYJK.11: End-to-end integration test `OperatorBookingFlowIT`: seed clinic + algorithm + operator-API-key → drive symptomizer to `BOOK_APPOINTMENT` conclusion → assert `booking_requests` row + `bot.booking.request_sent` outgoing → operator PATCH confirm → assert `bookings` row + two `reminders` rows + `bot.booking.confirmed` outgoing → fast-forward time (via `Clock` test double) → assert pre-visit reminder fires → fast-forward to `slot.end + 2h` → assert post-visit prompt fires → user replies "5" → assert `bookings.feedback_score = 5`. Runs against Testcontainers Postgres + `FakeLlmRouterClient` + `InMemoryClinicAdapter` (the port from FR-TNWP9R is wired but unused in this flow — kept for symmetry with M3+ automated flow).

## Technical Design

Builds on technical-spec.md § 2.1 + § 4.5. Specifically:

- **Module layout:** `core/src/main/kotlin/com/symptree/core/booking/{BookingHandoffService,FeedbackHandler}.kt`, `scheduler/ReminderScheduler.kt`, `db/{BookingRequestRepo,BookingRepo,ReminderRepo}.kt`, `routes/BookingRequestRoutes.kt`.
- **Schema split:** `booking_requests` (the operator's pending queue) is distinct from `bookings` (the confirmed appointments). Cancellation lands in `booking_requests.status = 'cancelled'` without ever creating a `bookings` row. This keeps the metrics clean (booking count = confirmed bookings, not raw requests).
- **Idempotency:** `booking_requests.id` is the operator-facing handle. `bookings` carries `booking_request_id` UNIQUE so re-confirming the same request returns the existing `bookings` row, never inserts a second.
- **Pushed-message topology:** the existing `MessagingPort` (bot module) gains no new method — `/bot-internal/outgoing` accepts a `catalog_key + params` payload and uses `MessagingPort.sendText` internally. This keeps the port shape stable for WhatsApp + future platforms.
- **Operator identifier:** for M2, `confirmed_by` is a free-form text string (e.g., the operator's email or name) passed in the PATCH body. No per-operator user model in M2 — the clinic's API key is the auth boundary. Per-operator audit + roles ship later.
- **Cancellation reason rendering:** `cancelled_reason` is passed verbatim from the operator into the patient message (interpolated into the catalog string). Operators write in the patient's language; M2 does not translate operator notes.
- **Feedback handler routing:** the bot module's incoming message handler needs to distinguish "post-visit feedback reply" from "new symptomizer session." When `sessions.history`'s most recent turn is a `post_visit_feedback` reminder ack, the next user message is routed to `FeedbackHandler` instead of `SessionService.applyStep`. Window: 24h after the feedback prompt fires.

## Testing

Per testing-spec.md. Specifically:

- Unit tests for `BookingHandoffService.createRequest` — happy + missing-session error.
- Unit tests for `BookingRequestRepo` (Testcontainers) — CRUD, cross-clinic isolation, status transitions, UNIQUE on `(booking_request_id)` for the bookings row.
- Route tests for `GET /booking-requests` + `PATCH /booking-requests/{id}` (Ktor `testApplication`) — covers 401 + 403 + 404 + 409 + 422 + 200 + idempotent re-PATCH.
- Concurrent-scheduler test per AC-4GCYJK.10 spins two `ReminderScheduler` coroutines against shared Postgres, inserts 100 due reminders, asserts each is sent exactly once.
- `FeedbackHandler` unit tests — happy 1-5 parse, out-of-range reprompt, timeout fallback to welcome menu.
- E2E test per AC-4GCYJK.11 in `src/test/kotlin/com/symptree/core/integration/OperatorBookingFlowIT.kt`.
- Same-day edge case: a confirmed slot < 24h out fires the pre-visit reminder immediately with the `same_day` catalog key — Testcontainers test asserts the right catalog key lands in the outgoing payload.

## Notes

- This FR replaces the automated-booking scope of archived FR-3AXJK9. Automated `proposeSlots`/`confirmBooking` via HL7v2 + doctor identity + slot-selection strategy moves to M3 FR-2VQ7CA.
- Patient `patient_contact_ref` is the Telegram user id for M2 — PII-light. When real HL7v2 vendors integrate in M3, this surface needs to grow into a fuller `PatientRef` (phone, MRN, full name) per ADT^A04 requirements.
- Operator-side admin UI is **out of scope**. The REST surface is the integration contract; clinics build their own dashboards or curl-test it manually for the pilot.
- Outbound webhook notification to the operator's clinic system (instead of polling) is a future option — the 2026-06-03 scope decision was polling-only for M2, with an opt-in webhook as a later FR.
- The `BOT_INTERNAL_SHARED_SECRET` is distinct from the `BOT_INGRESS_SHARED_SECRET` (bot → backend). Two secrets so a leak of one does not let the other direction be spoofed.
- `feedback_score` analytics + per-clinic NPS dashboards ship in a later milestone.
- Cancellation/reschedule by the *patient* (vs the operator) is not in M2 — patient just contacts the clinic directly. Bot-mediated reschedule is M3+.
