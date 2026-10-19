-- 06-migrate-doctor-schedule.sql
-- M2 / FR-YDANPW — internal scheduler inputs.
--
-- `doctor_weekly_hours` carries the recurring weekly availability; there is NO
-- unique on (doctor_id, day_of_week) because split shifts need multiple rows per
-- day. `doctor_time_off` carries one-off unavailability windows. CHECK
-- constraints reject inverted ranges. Applies on top of 05.
--
-- Time semantics (FR-YDANPW / FR-W03362; technical-spec.md § 3.4 Scheduling time model):
--   day_of_week  : 0=Sun .. 6=Sat (matches Postgres EXTRACT(DOW)), in clinic-local time.
--   start_time / end_time : LOCAL wall-clock (the clinic's timezone), NOT UTC. The anchoring
--     zone is clinics.config.preferences.timezone (IANA, default 'UTC'); the scheduler resolves
--     (date, row, zone) -> UTC Instant slots. No timezone column — it lives in clinics.config.

CREATE TABLE doctor_weekly_hours (
    id          BIGSERIAL PRIMARY KEY,
    doctor_id   BIGINT NOT NULL REFERENCES doctors(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    CHECK (end_time > start_time)
);

CREATE TABLE doctor_time_off (
    id        BIGSERIAL PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctors(id) ON DELETE CASCADE,
    start_at  TIMESTAMPTZ NOT NULL,
    end_at    TIMESTAMPTZ NOT NULL,
    reason    TEXT,
    CHECK (end_at > start_at)
);
