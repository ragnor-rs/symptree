-- 04-migrate-sessions-active-only-unique.sql
-- M2 / FR-PWVTT4 follow-up — fix the sessions UNIQUE constraint.
--
-- The original 03-migrate-sessions.sql declared
--   UNIQUE (platform, platform_user_id, clinic_id, status)
-- which the FR-PWVTT4 spec described as enforcing "one active session per
-- user per clinic per platform". The implementation didn't match the intent:
-- the broad four-column constraint also enforces "one CONCLUDED session per
-- (platform, user, clinic)", "one EXPIRED session" etc. — so the second time a
-- patient reaches a ConclusionNode, the `UPDATE ... SET status='concluded'`
-- collides with the previous concluded row and the route surfaces 500.
--
-- This migration replaces the broad constraint with a partial unique INDEX
-- restricted to active rows, matching the AC's stated intent:
--   "one active session per (platform, platform_user_id, clinic_id)".
-- Concluded / expired / future-status rows coexist freely (a patient can have
-- many concluded sessions over time as they walk the symptomizer repeatedly).
--
-- No data loss: the partial index only restricts future writes; existing
-- concluded rows stay put.

ALTER TABLE sessions
  DROP CONSTRAINT IF EXISTS sessions_platform_platform_user_id_clinic_id_status_key;

CREATE UNIQUE INDEX IF NOT EXISTS sessions_active_unique
  ON sessions (platform, platform_user_id, clinic_id)
  WHERE status = 'active';
