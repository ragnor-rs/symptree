-- 11-migrate-pending-declined-slots.sql
-- M2 bugfix / FR-W03362 AC.7 — remember EVERY slot a patient has declined in a
-- booking conversation, not just the latest.
--
-- Before this, `pending_bookings` tracked only `decline_count`, and the decline
-- re-propose excluded only the single just-declined slot. With ≥2 free slots the
-- re-proposal cycled back to an already-rejected time (e.g. 09:00 → 09:30 →
-- 09:00 again). Persisting the declined slot_at instants lets the re-propose
-- exclude all of them, so each "🔄 Другое время" advances to a genuinely new time.
--
-- Applies on top of 10. `declined_slots` holds the slot_at instants the patient
-- has declined; empty on a fresh proposal, preserved across re-proposals.

ALTER TABLE pending_bookings
    ADD COLUMN declined_slots TIMESTAMPTZ[] NOT NULL DEFAULT '{}';
