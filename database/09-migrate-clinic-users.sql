-- 09-migrate-clinic-users.sql
-- M2 / FR-MG7TSR — clinic-scoped human users for the admin panel login.
--
-- A `clinic_users` row is the human/browser auth boundary (distinct from the
-- machine per-clinic API key in clinics.config.api_key_hash, FR-M3WYNS).
-- `password_hash` is a bcrypt hash; plaintext is never stored or logged.
-- Email is unique PER CLINIC: the same address may exist in two clinics.
-- `role` is `admin` (full config) or `operator` (read results + manage bookings).
-- Applies on top of 08.

CREATE TABLE clinic_users (
    id            BIGSERIAL PRIMARY KEY,
    clinic_id     BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    email         TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('admin', 'operator')),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (clinic_id, email)
);
