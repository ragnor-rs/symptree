---
title: HL7v2 ClinicAdapter port + in-process mock
milestone: M3
status: active
archived_at: null
id: fr_01KT1YV4DCF3VR8RYECKTNWP9R
created_at: 2026-06-01T16:04:30Z
---

## Requirement

> **MOVED TO M3 + REFRAMED 2026-06-11 by the M2 admin-panel re-scope.** M2 no longer ships an HL7 mock — booking is internal (FR-W03362). The HL7v2 codec work below is preserved but repositioned: the `ClinicAdapter` port dissolves into FR-YDANPW's `MedicalDataSource` capability interfaces, so `Hl7v2ClinicAdapter` becomes `Hl7v2Provider` (implementing `SchedulingProvider` + `BookingProvider`) under `source/hl7v2/`, and `BookingResult.hl7AppointmentId` becomes the source-agnostic `BookingResult.externalRef`. The ACs below are re-specced against the new interfaces at M3 kickoff alongside FR-2VQ7CA; the segment-level HL7 design (SIU^S12 / ADT^A04 / ACK) carries over unchanged. HL7-specific names are correct because they live in the `hl7v2/` package.

Ship the `ClinicAdapter` port (vendor-neutral operations: list specialists, find slots, book appointment) and one concrete implementation: `Hl7v2ClinicAdapter` — an in-process mock that produces real HL7v2 messages via HAPI HL7v2 codec but does not actually send them over the wire. The codec is real (round-trippable SIU^S12 + ADT^A04); the transport is simulated. This lets the booking flow exercise the full HL7v2 message-construction path in tests + dev, while real wire transports (MLLPS, HTTPS-wrapped) land in M3.

References: requirements.md § 2.5 (no plaintext PII on the wire); technical-spec.md § 3.4 (`ClinicAdapter` port), § 3.6 (HL7v2 message profiles).

## Acceptance Criteria

- AC-TNWP9R.1: `ClinicAdapter` interface exists in `core/src/main/kotlin/com/symptree/core/clinic/ClinicAdapter.kt` matching the shape in technical-spec.md § 3.4: `listSpecialists()`, `findSlots(specialistCode, from, to)`, `bookAppointment(slot, patient, reason)`. All methods are `suspend`.
- AC-TNWP9R.2: `Specialist`, `Slot`, `PatientRef`, `BookingResult` data classes exist as per technical-spec.md § 3.4. `Specialist.displayName` is `Map<String, String>` (i18n).
- AC-TNWP9R.3: `Hl7v2Codec` builds well-formed SIU^S12 messages (segments: MSH, SCH, PID, PV1, RGS, AIS) and ADT^A04 messages (segments: MSH, EVN, PID, PV1) using the HAPI HL7v2 library (`ca.uhn.hapi:hapi-base` + `hapi-structures-v25`). Built messages parse back through HAPI cleanly (round-trip byte-equal for the canonical encoding).
- AC-TNWP9R.4: `Hl7v2Codec` parses HL7v2 ACK messages (AA / AE / AR) and produces a typed result: `AckResult.Accepted(controlId)`, `AckResult.AppError(errorText)`, `AckResult.Rejected(errorText)`. Unknown ACK codes throw `Hl7ProtocolException`.
- AC-TNWP9R.5: `Hl7v2ClinicAdapter` implements `ClinicAdapter`. `listSpecialists()` reads from `clinics.config.specialists`. `findSlots(specialistCode, from, to)` synthesizes deterministic slots (every weekday in the range, slots at hour boundaries between 09:00 and 18:00 in the clinic's timezone, configurable in `clinics.config.preferences.booking_lookahead_days` — capped to that horizon). `bookAppointment(slot, patient, reason)` formats the SIU^S12 (and ADT^A04 if `patient.externalId == null`), logs the wire-format bytes at DEBUG, returns a `BookingResult` with a synthetic `hl7AppointmentId = UUID.randomUUID().toString()`.
- AC-TNWP9R.6: An `Hl7v2ClinicAdapterMessageTrace` (internal mock-only) records the last N messages that would have been sent. Used by booking-flow tests to assert "an SIU^S12 was produced with these segment values" without needing a real HL7v2 server.
- AC-TNWP9R.7: Round-trip tests against reference HL7v2 fixtures in `src/test/resources/hl7/` (sample SIU^S12 + ADT^A04 + ACK^AA / AE / AR messages, sourced from public HL7v2 test corpus) — parse → re-serialize → byte-equal after canonical re-encoding.
- AC-TNWP9R.8: The `ClinicAdapter` port is exercised in M2 only via `InMemoryClinicAdapter` (per testing-spec.md § 5.6) — M2's user-facing flow is operator-assisted (FR-4GCYJK), so HL7v2 messages are not produced in production paths. `Hl7v2ClinicAdapter` is exercised by a dedicated wire-format integration test asserting SIU^S12 / ADT^A04 / ACK byte correctness. M3 FR-2VQ7CA wires `Hl7v2ClinicAdapter` (or future vendor subclasses) into the automated booking flow.

## Technical Design

Builds on technical-spec.md § 3.4 + § 3.6. Specifically:

- **Module layout:** `core/src/main/kotlin/com/symptree/core/clinic/{ClinicAdapter,Hl7v2ClinicAdapter,Hl7v2Codec,Slot}.kt`.
- **HAPI HL7v2 usage:** instantiate a `ca.uhn.hl7v2.HapiContext` once at startup; reuse for parse + build. Use v2.5 message structures (HAPI's `hapi-structures-v25`). Encoding: ER7 (pipe-delimited) is the canonical wire format; XML encoding deferred to vendor-specific need.
- **SIU^S12 segment population:**
  - MSH: sending app/facility from `clinics.config.hl7`; receiving app/facility hardcoded as `"SYMPTREE"`; message control id is `UUID`.
  - SCH: filler-appointment-id is the synthetic `hl7AppointmentId`; appointment-reason is the symptomizer conclusion's specialist string.
  - PID: patient external-id if known; patient name from `PatientRef.displayName`; phone from `PatientRef.phone` (encrypted before persisting to bookings).
  - PV1: patient class `"O"` (outpatient).
  - RGS / AIS: specialist resource group + service identifier mapping from `clinics.config.specialists[specialistCode]`.
- **ADT^A04 segments:** sent only when `patient.externalId == null` (patient is unknown to the HIS).
- **Timezone:** all slot computations honor the clinic's timezone (`clinics.config.preferences.timezone`, default `"Europe/Moscow"`). Slots are stored in UTC in the `bookings` table; rendered to the user in clinic-local time.
- **Mock transport:** `Hl7v2ClinicAdapter.transport.send(message)` is a no-op that logs at DEBUG. Real impls (M3+) replace this with MLLPS / HTTPS-wrapped HL7v2.
- **In-memory adapter:** `InMemoryClinicAdapter` is a test-only sibling implementation in `src/test/kotlin/.../clinic/InMemoryClinicAdapter.kt`. Same port; deterministic slot calendar; no HL7v2 codec involved. Booking-flow tests use this for unit/IT-level speed.

## Testing

Per testing-spec.md. Specifically:

- Round-trip codec tests per AC-TNWP9R.7. Reference fixtures live in `src/test/resources/hl7/` and are sourced from the public HL7v2 reference messages bundled with HAPI's test suite. Tests live in `src/test/kotlin/com/symptree/core/clinic/Hl7v2CodecRoundTripTest.kt`.
- `Hl7v2ClinicAdapter` tests: each port method, deterministic-slot expectations, message-trace recording assertions.
- Integration test per AC-TNWP9R.8 final clause: `BookingFlowHl7IT` exercises the full booking flow through `Hl7v2ClinicAdapter` with a Testcontainers Postgres, asserts the SIU^S12 was produced with the right segment values, and the booking row was written with the synthetic `hl7AppointmentId`.
- Negative tests: ACK^AE / AR responses translate to `BookingResult` failures the booking-flow layer can render to the user.

## Notes

- M2 surface is SIU^S12 + ADT^A04 + slot-query-via-port. SIU^S15 cancel + SIU^S13 reschedule + ORU^R01 ICD-result intake all M3+.
- HL7v2.5 is chosen as the message structure version since most Turkish HBYS vendors target 2.3-2.5 range; 2.x.x within HAPI is mutually compatible at the segment level.
- A concrete vendor adapter lands in M3; integrating any specific HIS requires that vendor's interface documentation. Until then `Hl7v2ClinicAdapter`'s mock transport is the only impl shipped.
- A vendor-conformance test corpus (vendor-specific SIU^S12 acceptance criteria) is a downstream M3 artifact and out of scope here.
- `org.hl7.fhir`-based alternatives (FHIR R4 Appointment + Slot + Schedule) were considered but rejected for M2, since HL7v2 is what the target HIS market actually speaks. FHIR remains a future option once a FHIR-capable vendor is on the integration roadmap.
