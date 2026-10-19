-- 05-migrate-doctors.sql
-- M2 / FR-YDANPW — canonical doctor catalog.
--
-- Introduces `doctor_identity` (one stable identity per real doctor) and the
-- `doctors` catalog rows that reference it. A doctor's identity survives
-- re-upserts (keyed on source_id + external_id) so downstream references stay
-- stable across syncs. `specialist_codes` are free-form strings matched against
-- `ConclusionNode.specialist` (no enum). Applies on top of 01–04.

CREATE TABLE doctor_identity (
    id           BIGSERIAL PRIMARY KEY,
    clinic_id    BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    display_name TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE doctors (
    id               BIGSERIAL PRIMARY KEY,
    clinic_id        BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    identity_id      BIGINT NOT NULL REFERENCES doctor_identity(id) ON DELETE CASCADE,
    source_id        TEXT NOT NULL,
    external_id      TEXT NOT NULL,
    specialist_codes TEXT[] NOT NULL DEFAULT '{}',
    slot_minutes     INT NOT NULL,
    full_name        TEXT NOT NULL,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at       TIMESTAMPTZ,
    synced_at        TIMESTAMPTZ,
    sync_cursor      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (source_id, external_id)
);
