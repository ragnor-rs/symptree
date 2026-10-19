---
title: Automated booking + doctor identity + slot-selection strategy
milestone: M3
status: active
archived_at: null
id: fr_01KT6HM4W3JVDYNFJ96V2VQ7CA
created_at: 2026-06-03T10:49:16Z
---

## Requirement

> **PARTIALLY OVERTAKEN 2026-06-11 by the M2 admin-panel re-scope.** Two of the three axes below shipped in M2: **doctor identity** now lands as FR-YDANPW's canonical doctor catalog (`doctor_identity` + per-source `doctors` rows), and **automated `first_available` booking** ships internally in FR-W03362 (auto-assign earliest + confirm). What remains uniquely M3 here: (a) automated booking against an **external** `BookingProvider` (HL7v2/CRM) rather than the internal scheduler, and (b) the `round_robin` / `least_busy` slot strategies. The port reference below changes from `ClinicAdapter` to FR-YDANPW's `MedicalDataSource` capability interfaces (`SchedulingProvider` / `BookingProvider`). Re-specced at M3 kickoff.

Move from operator-assisted booking (M2 FR-4GCYJK) to fully automated booking via the `ClinicAdapter` port. Adds three product axes deferred from M2 to M3 by explicit scope decision on 2026-06-03:

1. **Automated proposeSlots/confirmBooking** — patient picks a slot directly in the bot; backend books via `ClinicAdapter.bookAppointment` (HL7v2 SIU^S12 or vendor REST). Replaces the operator-handoff handoff message with a slot menu + booking confirmation. Origin: ACs .2, .3, .4 of archived FR-3AXJK9.
2. **Doctor identity** — `Doctor` becomes a first-class entity. A clinic has many doctors; each doctor has their own schedule; a specialty (e.g., "neurologist") can be staffed by multiple doctors. `Slot` carries `doctor_id`. Booking confirmation names the specific doctor, not just the specialty.
3. **Slot-selection strategy** — clinic-configurable allocation algorithm: `first_available` (current default), `round_robin` (distribute load), `least_busy` (prefer doctors with empty schedules — addresses new-doctor onboarding, where a newly-added doctor can otherwise sit unbooked for a long stretch while `first_available` keeps favouring established doctors).

The M2 reminder + post-visit-feedback infrastructure (FR-4GCYJK ACs .5–.11) is reused unchanged — automated booking just replaces the request → confirm step, not the post-confirm flow.

References: archived FR-3AXJK9 (origin of automated ACs); FR-4GCYJK (M2 operator-assisted predecessor — reminders/feedback infrastructure); FR-TNWP9R (HL7v2 codec foundation shipped in M2).

## Acceptance Criteria

- AC-2VQ7CA.1: `Doctor` entity table + migration. Schema: `id`, `clinic_id` (FK), `external_id` (vendor system reference), `full_name` (JSONB i18n), `specialist_codes` (text array — one doctor can hold multiple specialties), `active_from`, `active_to` (NULL = currently active), `created_at`. Cross-clinic isolation enforced.
- AC-2VQ7CA.2: Clinic-admin REST: `POST/GET/PATCH /api/v1/clinics/{slug}/doctors` — CRUD for clinic-admin to register doctors and toggle active status. Clinic-API-key authed.
- AC-2VQ7CA.3: `Slot` data class gains `doctor_id: Long` field. `ClinicAdapter.findSlots(specialistCode, from, to)` is replaced by `ClinicAdapter.findSlotsForClinic(clinicId, specialistCode, from, to)` which returns slots attributed to specific doctors. `Hl7v2ClinicAdapter` (from FR-TNWP9R) gets updated to synthesize per-doctor schedules.
- AC-2VQ7CA.4: `clinics.config.booking.slot_strategy` enum field (validated on PUT): `first_available` (preserves M2 behavior — first chronological slot from the doctor with most availability), `round_robin` (cycle doctors in registration order, even if some have earlier slots), `least_busy` (prefer doctor with fewest confirmed bookings in the lookahead window — drives patients to underutilized doctors). Default `first_available` for backwards compatibility.
- AC-2VQ7CA.5: `BookingService.proposeSlots(sessionId, conclusion)` queries all active doctors at the clinic carrying the conclusion's `specialist` code, calls `ClinicAdapter.findSlotsForClinic` per doctor, applies `slot_strategy` to filter/reorder, returns top-N slots as a `SendMenu` outgoing action. Each slot menu item is labeled with doctor name + time (`"Доктор Иванова — 15 июня 10:00"`).
- AC-2VQ7CA.6: `BookingService.confirmBooking(sessionId, slotId)` calls `ClinicAdapter.bookAppointment(slot, patientRef, reason)`, inserts a `bookings` row referencing the chosen doctor + `hl7_appointment_id`, reuses FR-4GCYJK's reminder + post-visit feedback infrastructure unchanged. On adapter failure (HL7 NACK / network error / vendor-specific), returns a user-facing failure message and leaves the session in `pending_booking` so the user can retry.
- AC-2VQ7CA.7: `clinics.config.booking.automated_enabled: bool` (default false) controls whether the bot enters automated flow (this FR) or operator-handoff flow (FR-4GCYJK). Migration path: clinics opt in per-clinic; both flows coexist indefinitely in the codebase. The conclusion's `BOOK_APPOINTMENT` branch reads the flag and routes accordingly.
- AC-2VQ7CA.8: `bookings.scheduled_via` enum (`operator` / `automated`) distinguishes the two paths in the data layer so analytics can compare conversion + show-up rates.
- AC-2VQ7CA.9: `PatientRef` data class grows to cover HL7v2 ADT^A04 requirements: `phone`, `full_name` (collected via a one-time profile flow on first booking), `external_id` (vendor MRN if known), `birthdate` (optional, gathered if required by the clinic). M2's PII-light Telegram-id-only shape is preserved as the operator-flow ref; only automated flow demands the fuller surface.
- AC-2VQ7CA.10: Slot-strategy unit tests — happy path for each of the three strategies against a synthetic 3-doctor fixture: assert first-available picks earliest-slot doctor, round-robin cycles, least-busy picks the doctor with zero existing bookings.
- AC-2VQ7CA.11: End-to-end integration test `AutomatedBookingFlowIT`: seed clinic + 3 doctors + `automated_enabled: true` + `slot_strategy: least_busy` → drive symptomizer to `BOOK_APPOINTMENT` → assert proposed slots prefer the empty-schedule doctor → confirm → assert `bookings.doctor_id` matches expected + `scheduled_via='automated'` + HL7 SIU^S12 trace recorded.

## Technical Design

Builds on FR-4GCYJK's reminder infrastructure and FR-TNWP9R's HL7v2 codec.

- **Migration:** `database/0X-migrate-doctors.sql` creates the table; `0Y-migrate-bookings-doctor-ref.sql` adds `bookings.doctor_id` (nullable to preserve M2 operator bookings — operator-flow bookings continue to write NULL).
- **Slot strategy interface:**
  ```kotlin
  sealed interface SlotStrategy {
      fun rank(slots: List<Slot>, doctorBookingCounts: Map<Long, Int>): List<Slot>
      object FirstAvailable : SlotStrategy
      object RoundRobin : SlotStrategy
      object LeastBusy : SlotStrategy
  }
  ```
- **HL7v2 ADT^A04 patient registration:** if `patient.external_id == null`, send ADT^A04 first (creates patient record at vendor); then SIU^S12. If `external_id` is known, skip ADT^A04. FR-TNWP9R already builds both message types.
- **Doctor activation rollover:** clinics add new doctors via `POST /doctors`; existing pending booking requests do not retroactively route to new doctors (they're operator-handled via the M2 surface unless the clinic explicitly re-runs slot resolution).

Open design questions (resolve at implementation time):

- **Per-doctor schedule storage** — store in `clinics.config.specialists` (current M2 model, indirect) or promote to a first-class `doctor_schedules` table with explicit shift records? Likely the latter, but the schema needs piloting against real vendor data.
- **Patient profile collection UX** — automated flow needs the user's full name + phone for HL7. Block first booking on a profile-completion mini-flow, or collect lazily on first slot pick?
- **Strategy-config UX** — does the clinic operator pick via clinic-admin REST PUT, or does it land in a clinic settings dashboard (M3+ UI work)?

## Testing

Per testing-spec.md.

- Unit tests for each `SlotStrategy` against synthetic fixtures.
- Integration tests for `DoctorRepo` (Testcontainers) — CRUD + cross-clinic isolation + activation-period filtering.
- Route tests for `/doctors` admin endpoints.
- `AutomatedBookingFlowIT` per AC-2VQ7CA.11.
- HL7v2 wire-format assertion: synthesize a booking confirmation, parse the produced SIU^S12 via the M2 `Hl7v2Codec`, assert the PV1 segment names the chosen doctor.

## Notes

- Deferred from M2 by explicit scope decision on 2026-06-03: manual booking was MVP-mandatory and automated CRM integration was not, while the doctor-identity and load-balancing requirements that emerged did not fit the M2 scope.
- The `slot_strategy` enum is the minimum useful set; richer strategies (specialist-availability prediction, patient-preference matching, geographic optimization for multi-location clinics) defer to later milestones.
- Real HL7v2 vendor integration (e.g., a specific MIS) is a separate FR — this FR works against the synthetic `Hl7v2ClinicAdapter` shipped in M2 FR-TNWP9R + any future vendor-specific subclass.
- The operator-handoff flow (FR-4GCYJK) is **not** deprecated when automated arrives — both coexist. Some clinics may always prefer manual oversight; pilot data will tell.
- Per-clinic CRM REST adapter is a separate FR — the abstraction is the `ClinicAdapter` port, so adding a CRM-backed implementation is orthogonal to this FR's scope.
