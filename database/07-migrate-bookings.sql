-- 07-migrate-bookings.sql
-- M2 / FR-W03362 — source-agnostic bookings.
--
-- The booking row is SOURCE-AGNOSTIC: no provider-type name (no hl7_* / siu /
-- adt / mllp / his column). A booking references its owning source via
-- `source_id` ('internal' in M2; a vendor source id later) and the doctor within
-- that source via `external_doctor_id`. `external_appointment_id` is the owning
-- source's appointment ref and is NULL for the internal source (symptree is the
-- calendar of record). `doctor_identity_id` is a nullable display/aggregation FK.
--
-- Double-booking is prevented only where symptree owns the calendar (the internal
-- source): the partial EXCLUDE USING gist constraint rejects overlapping
-- confirmed bookings on the same doctor. External mirror rows carry no such
-- guarantee (the vendor calendar is the authority). Requires btree_gist for the
-- `=` operator on the scalar `external_doctor_id` alongside the `&&` range op.
-- Applies on top of 06.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE bookings (
    id                      BIGSERIAL PRIMARY KEY,
    clinic_id               BIGINT NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    session_id              BIGINT NOT NULL REFERENCES sessions(id) ON DELETE RESTRICT,
    source_id               TEXT NOT NULL,        -- 'internal' | vendor source id
    external_doctor_id      TEXT NOT NULL,        -- doctor ref within the owning source
    doctor_identity_id      BIGINT REFERENCES doctor_identity(id),  -- display/aggregation (nullable)
    specialist_code         TEXT NOT NULL,
    slot_at                 TIMESTAMPTZ NOT NULL,
    slot_end                TIMESTAMPTZ NOT NULL,
    external_appointment_id TEXT,                 -- owning source's appt ref; NULL for internal
    status                  TEXT NOT NULL DEFAULT 'confirmed',  -- 'confirmed' | 'cancelled' | 'completed'
    feedback_score          SMALLINT,             -- 1..5, post-visit
    feedback_text           TEXT,
    icd_code                TEXT,                 -- populated after visit (M3+)
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- double-booking prevented only where symptree owns the calendar (internal source)
    EXCLUDE USING gist (
        external_doctor_id WITH =,
        tstzrange(slot_at, slot_end) WITH &&
    ) WHERE (source_id = 'internal' AND status = 'confirmed')
);

CREATE INDEX bookings_clinic_session ON bookings(clinic_id, session_id);
