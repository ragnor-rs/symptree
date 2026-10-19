-- 10-migrate-pending-bookings.sql
-- M2 / FR-W03362 AC.5 — transient per-session "awaiting confirm/decline" state.
--
-- When the symptomizer reaches a BOOK_APPOINTMENT conclusion the backend
-- auto-assigns the earliest slot and asks the patient to confirm. That proposed
-- slot (+ how many times the patient has declined so far) is held here until the
-- patient confirms (→ a `bookings` row is written, this row deleted) or the
-- decline cap is hit (→ contact-clinic fallback, this row deleted). One pending
-- proposal per session (UNIQUE session_id); a re-propose upserts the same row.
--
-- The slot is denormalized (doctor ref + start/end + specialist) so the confirm
-- turn can re-issue the booking without re-running availability. Applies on top
-- of 08; numbered 10 to leave 09 for FR-MG7TSR's clinic_users migration.

CREATE TABLE pending_bookings (
    id                  BIGSERIAL PRIMARY KEY,
    clinic_id           BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    session_id          BIGINT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    specialist_code     TEXT NOT NULL,
    source_id           TEXT NOT NULL,
    external_doctor_id   TEXT NOT NULL,
    doctor_identity_id  BIGINT REFERENCES doctor_identity(id),
    doctor_display_name TEXT NOT NULL,
    slot_at             TIMESTAMPTZ NOT NULL,
    slot_end            TIMESTAMPTZ NOT NULL,
    decline_count       INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (slot_end > slot_at),
    UNIQUE (session_id)
);
