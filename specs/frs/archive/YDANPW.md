---
title: Medical data-source abstraction + canonical doctor catalog
milestone: M2
status: archived
archived_at: 2026-06-17T14:33:06Z
id: fr_01KTVA849JNSJ71V15AXYDANPW
created_at: 2026-06-11T12:24:26Z
---

## Requirement

Introduce a **`MedicalDataSource`** abstraction as the umbrella over the clinic's care-provider data, replacing the narrow HL7v2-flavored `ClinicAdapter` port (technical-spec.md § 3.4; the HL7 codec work of FR-TNWP9R is deferred to M3 and becomes the future `Hl7v2Provider`). A `MedicalDataSource` is a per-clinic **composition of capability providers** — `DoctorDirectory`, `SchedulingProvider`, `BookingProvider` (and `PatientRecordProvider` later) — each independently backed. A clinic owns a *set* of sources; aggregators federate over them. This lets a clinic mix internally-defined doctors with doctors surfaced from an external HIS (HL7v2) or CRM (REST) under one admin panel, where any single capability can be bound to a different backend (e.g. directory from REST CRM, booking via HL7v2).

This FR lands the **canonical doctor catalog schema now** (per project-owner decision 2026-06-11: "keep the architecture ready for future developments — the canonical doctor schema should already land") so that multi-source federation, full→incremental directory sync, and manual cross-source dedup are *additive* later rather than a migration. The MVP ships **only the `INTERNAL` source** implementation; the registry holds exactly one source, and the federation/sync runtime is deferred until a real second source exists (approach 2 "schema-first" from the 2026-06-11 brainstorm).

A hard invariant: **no provider-type name leaks into canonical symbols.** Provider-specific names (HL7, SIU, ADT, MLLP, HIS) live only inside their own implementation package (`source/hl7v2/`) and the wire codec — never in `source/` root types, the Postgres schema, or the service layer.

References: brainstorm 2026-06-11 (MedicalDataSource composition, mirror+cache, schema-first); technical-spec.md § 3.4 (`ClinicAdapter` port being repositioned); FR-TNWP9R (HL7v2 codec — deferred to M3, becomes the future `Hl7v2Provider` impl under this abstraction); FR-2VQ7CA (M3 — doctor identity + slot strategies, now building on this abstraction); requirements.md § 2.1 (multi-tenancy by `clinic_id`), § 2.2 (i18n).

## Acceptance Criteria

- AC-YDANPW.1: Capability-provider interfaces defined in `core/.../source/`: `DoctorDirectory { suspend fun listDoctors(clinicId): List<DoctorRef> }`, `SchedulingProvider { suspend fun availability(doctorRef, window): List<Slot> }`, `BookingProvider { suspend fun book(slot, patient): BookingResult; suspend fun cancel(ref) }`. Each is a thin, independently-implementable interface (interface-segregation — no fat interface that forces `NotImplemented`).
- AC-YDANPW.2: `MedicalDataSource` is a composition binding an optional provider per capability: `id: SourceId`, `kind: SourceKind`, `directory: DoctorDirectory?`, `scheduling: SchedulingProvider?`, `booking: BookingProvider?`. **M2 ships `SourceKind { INTERNAL }` only** — `kind` is the adapter+connection-params discriminator, and further kinds (a standard-format family backing many external provider instances through one adapter, plus per-vendor CRM kinds) land in M3 *with their adapters*, so no provider-named enum value exists in M2 canonical code (this is what keeps AC-YDANPW.9's leak guard green; see technical-spec.md § 3.4 for the named future kinds + `connection_params` model). A `DataSourceRegistry(sources: List<MedicalDataSource>)` holds a clinic's sources; for MVP it is constructed with exactly one `INTERNAL` source, and its accessors (`listAllDoctors`, booking source-routing) read the registry rather than a concrete provider. **Genuine multi-source federation (≥2 sources of differing kinds, cross-source routing, directory sync, identity dedup) is deferred to M3** — the registry method shapes are the seam M3 extends.
- AC-YDANPW.3: Source-agnostic shared types live in `source/`: `DoctorRef`, `Slot`, `PatientRef`, `BookingResult`. `BookingResult` exposes `externalRef: String?` (the owning source's appointment reference — null for internal) — **not** `hl7AppointmentId`. No type in `source/` root carries a provider-specific name.
- AC-YDANPW.4: Migration `database/05-migrate-doctors.sql` creates the canonical catalog: `doctor_identity` (`id`, `clinic_id` FK, `display_name`, `created_at`, `updated_at`) and `doctors` (`id`, `clinic_id` FK, `identity_id` FK → `doctor_identity`, `source_id`, `external_id`, `specialist_codes TEXT[]`, `slot_minutes INT`, `full_name`, `active BOOLEAN`, `deleted_at TIMESTAMPTZ`, `synced_at TIMESTAMPTZ`, `sync_cursor TEXT`, `created_at`). Composite natural key `UNIQUE (source_id, external_id)`. Cross-clinic isolation: every row carries `clinic_id`.
- AC-YDANPW.5: Migration `database/06-migrate-doctor-schedule.sql` creates `doctor_weekly_hours` (`id`, `doctor_id` FK, `day_of_week SMALLINT` 0–6, `start_time TIME`, `end_time TIME`) — multiple rows per `(doctor_id, day_of_week)` express split shifts; absence of a row = a recurring day off — and `doctor_time_off` (`id`, `doctor_id` FK, `start_at TIMESTAMPTZ`, `end_at TIMESTAMPTZ`, `reason TEXT`) for one-off absences. `CHECK (end_time > start_time)` and `CHECK (end_at > start_at)`.
- AC-YDANPW.6: `specialist_codes` values are matched against the symptomizer's `ConclusionNode.specialist` (the "doctor type"). A `DoctorRef` carries the union of its identity's specialist codes. No new specialist enum is introduced — the existing free-form specialist code from the algorithm trees is authoritative.
- AC-YDANPW.7: Canonical identity is 1:1 in MVP — each new `doctors` row auto-creates its own singleton `doctor_identity`. Sync (FR-future) only ever upserts `doctors` keyed on `(source_id, external_id)`; the `identity_id` assignment survives re-sync (upsert never clears it). Manual cross-source merge (re-pointing rows at a shared identity) is **deferred** to the FR that lands the second source — internal-only MVP has no duplicates.
- AC-YDANPW.8: `InternalDoctorDirectory` implements `DoctorDirectory` over `DoctorRepo` (raw JDBC), returning active, non-deleted doctors for a clinic. `DoctorRepo` covers CRUD + clinic-scoped lookup + specialist-code filter, Testcontainers-tested with cross-clinic isolation.
- AC-YDANPW.9: Provider-name-leak guard: a `NoProviderNameLeakTest` regex-sweeps `source/` (excluding the `source/hl7v2/` subpackage) + the Kotlin DB models + the `database/*.sql` schema files and fails on any of `hl7`, `siu`, `adt`, `mllp`, `\bhis\b` (case-insensitive) appearing in a symbol name or column name. Deliberately adding `hl7AppointmentId` to a `source/` root type fails the test.
- AC-YDANPW.10: The old `ClinicAdapter` port and `BookingResult.hl7AppointmentId` are removed from technical-spec.md § 3.4 and replaced by this FR's abstraction; the directory tree's `clinic/  # HL7v2 adapter` package becomes `source/` with `internal/` (shipped) + `hl7v2/` (deferred) subpackages. No dangling reference to `ClinicAdapter` remains in active specs (archived FRs excepted).

## Technical Design

Builds on technical-spec.md § 1 (module layout), § 2.1 (schema), § 3.4 (the port being repositioned), § 4.4 (multi-tenancy).

- **Package layout** (`core/src/main/kotlin/com/symptree/core/source/`):
  ```
  source/
    MedicalDataSource.kt      # composition: binds providers per capability
    DataSourceRegistry.kt     # per-clinic set of sources (single-source in M2; federation is M3)
    SourceId.kt  SourceKind.kt  ExternalApptRef.kt   # SourceKind { INTERNAL } in M2
    DoctorDirectory.kt  SchedulingProvider.kt  BookingProvider.kt
    DoctorRef.kt  Slot.kt  PatientRef.kt  BookingResult.kt   # source-agnostic types
    internal/
      InternalDoctorDirectory.kt    # DoctorDirectory over DoctorRepo (this FR)
      # InternalScheduler.kt        # SchedulingProvider + BookingProvider — FR-W03362
    <kind>/                         # DEFERRED (M3) — each future provider gets its own subpackage
  ```
  `SourceKind` ships as `{ INTERNAL }` only; provider-named kinds (and their adapter subpackages) are added in M3 so no provider name leaks into M2 canonical code — not even in comments (the leak guard sweeps `source/` doc-comments too, so the named future kinds live in technical-spec.md § 3.4, not here).
- **Composition over capability flags:** binding providers per capability (rather than one `MedicalDataSource` that *implements* all capabilities) is what makes the mixed case expressible — HL7v2 can back `booking`+`scheduling` while REST CRM backs `directory`. The HL7v2 codec genuinely cannot list a doctor directory, so it simply has no `directory` binding.
- **Identity layer above source rows:** `doctors` are raw per-source projection rows; `doctor_identity` is the canonical grouping. Bookings (FR-W03362) reference the **source row** `(source_id, external_doctor_id)`, not the identity — you always book into exactly one source's calendar — so the identity layer is a read/display/aggregation overlay and adding cross-source merge later does not move any booking FK.
- **Forward-investment columns:** `deleted_at` (soft-delete, so full-relist sync can expire absent rows) and `synced_at` / `sync_cursor` (per-source watermark) land now though MVP's internal source barely uses them — they are the only schema cost of making incremental sync a drop-in later.
- **Reposition, don't reimplement:** `ClinicAdapter` was never built (FR-TNWP9R is unbuilt). This FR defines the new interfaces and the `INTERNAL` source's directory; the HL7 codec work moves verbatim into `source/hl7v2/` when M3 picks it up.

## Testing

Per testing-spec.md.

- `DoctorRepoIT` (Testcontainers) — CRUD, clinic-scoped lookup, specialist-code filter, soft-delete exclusion, cross-clinic isolation, `(source_id, external_id)` uniqueness, identity auto-creation on insert.
- `DoctorScheduleRepoIT` — weekly-hours split-shift rows, time-off rows, the two `CHECK` constraints reject inverted ranges.
- `InternalDoctorDirectoryTest` — `listDoctors` returns only active/non-deleted; `DoctorRef` carries the specialist-code union.
- `DataSourceRegistryTest` — the single INTERNAL source's doctors are returned by `listAllDoctors`; booking-route resolves a doctor to its owning source's `booking` provider (and returns null with no binding). Multi-source scenarios (differing kinds, directory-less source contributes zero, cross-source routing) move to M3 with the second source's adapter — tracked in specs/plan/M3.md.
- `NoProviderNameLeakTest` per AC-YDANPW.9.
- Migration apply test: `05` + `06` apply cleanly after `01–04` (the doctor migrations are numbered `05`/`06`, not `04`/`05` — `04` was already taken by `04-migrate-sessions-active-only-unique.sql` from FR-PWVTT4).

## Notes

- **M2 scope line (2026-06-11 owner decision):** the HL7v2 source-kind + adapter *and* the multi-source federation runtime move to M3. M2/YDANPW ships only the `INTERNAL` source-kind, the canonical doctor catalog schema, and `InternalDoctorDirectory`; `DataSourceRegistry` is a single-source holder. This is what makes `SourceKind { INTERNAL }` correct now (no provider-named enum value, leak guard green).
- The `Hl7v2Provider` + `Hl7v2Codec` (FR-TNWP9R, moved to M3) become the second `BookingProvider`/`SchedulingProvider` implementation, landing under its own `source/<kind>/` subpackage — deferred, not deleted. Provider-specific names are correct *there*. REST CRMs are not a standard, so they do **not** share one generic kind — each CRM integration is its own kind + adapter, added per-integration in M3 (no speculative `REST_CRM` placeholder ships in M2).
- FR-2VQ7CA (M3) — slot-selection strategies (`round_robin`, `least_busy`) and the external-source provider — now builds on this abstraction rather than on `ClinicAdapter`. The `first_available` strategy ships internally in FR-W03362.
- Manual cross-source dedup UI + the merge/unmerge actions on `doctor_identity` are a fast-follow that lands with the second source (it must be sync-stable — merge re-points rows, never deletes synced source rows).
- Directory caching/mirror semantics (panel reads a local projection, never hits a vendor live) become load-bearing only when an external `DoctorDirectory` exists; for the internal source the `doctors` table *is* the source of truth.

## Implementation notes

**Schedule-schema time semantics (decided 2026-06-11; consumed by FR-W03362):** `doctor_weekly_hours.day_of_week` uses **0=Sun .. 6=Sat** (matches Postgres `EXTRACT(DOW)`), evaluated in clinic-local time. `start_time`/`end_time` are **local wall-clock** (the time the admin types), NOT UTC — they carry no zone of their own. The anchoring zone is the **clinic's IANA timezone** in `clinics.config.preferences.timezone` (default `'UTC'`), resolved to UTC `Slot` `Instant`s by the scheduler (FR-W03362). No timezone column is added — it lives in the existing `config` JSONB. Full model + worked example: technical-spec.md § 3.4 *Scheduling time model*.

Advisory findings from the YDANPW implementation review (carry-forward, non-blocking):

- **Global natural key — internal `external_id` must be globally unique (for FR-MG7TSR):** `UNIQUE (source_id, external_id)` is global, not clinic-scoped (per AC-YDANPW.4). So the admin-panel FR that creates internal doctors must assign a **globally-unique** `external_id` (e.g. a UUID), not a per-clinic counter — otherwise two clinics' internal doctors with the same `source_id='internal'` + counter would collide. Schema is correct as specified; this is a constraint on the *writer*.
- **`DoctorRepo.upsert` concurrent same-key orphan — FIXED:** previously two upserts of the same `(source_id, external_id)` could race, both see "no existing row," both create a fresh identity, and the identity-preserving `ON CONFLICT DO UPDATE` would leave the loser's identity orphaned. Resolved with a per-key transaction-scoped `pg_advisory_xact_lock(hashtext(source_id|external_id))` at the head of `upsert`, which serializes concurrent upserts of one key (no-op for single-threaded callers, released at commit/rollback). Verified by `DoctorRepoIT` "concurrent upserts of the same key yield exactly one identity (no orphan)".
