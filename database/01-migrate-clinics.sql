-- 01-migrate-clinics.sql
-- M2 / FR-M3WYNS — multi-tenant clinic model.
--
-- Creates the `clinics` table plus a minimal `algorithms` shell and the
-- `clinic_algorithms` join table. The algorithms shell is intentionally minimal
-- here; the full algorithms surface (version, metadata, tree JSONB) is shipped
-- by FR-KT4697 in a later migration.

CREATE TABLE clinics (
    id                BIGSERIAL PRIMARY KEY,
    slug              TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    default_language  TEXT NOT NULL,
    allowed_languages TEXT[] NOT NULL,
    config            JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Minimal algorithms shell — full surface (version, metadata, tree) lands in FR-KT4697.
CREATE TABLE algorithms (
    id            BIGSERIAL PRIMARY KEY,
    algorithm_key TEXT NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (algorithm_key)
);

CREATE TABLE clinic_algorithms (
    clinic_id    BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    algorithm_id BIGINT NOT NULL REFERENCES algorithms(id) ON DELETE RESTRICT,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (clinic_id, algorithm_id)
);
