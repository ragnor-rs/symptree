-- 08-migrate-reminders.sql  (FR-W03362; reused from archived FR-4GCYJK)
-- M2 — the `reminders` outbox the in-process ReminderScheduler coroutine drains.
--
-- FR-VWRNDR — `payload` (JSONB) carries **semantic** fields only:
--   { platform_user_id, language, kind, doctor_name, slot_at, clinic_zone }
-- where `slot_at` is epoch-millis and `kind` ∈ pre_visit_24h | same_day |
-- post_visit_feedback. No `catalog_key` and no view format are stored — the
-- messaging frontend maps `kind` to its own catalog key and formats `slot_at`
-- in `clinic_zone` at dispatch time. The shape is self-contained so the poll
-- builds the outbound message without re-reading the session. (Pre-release M2:
-- this superseded W03362's interim catalog_key payload; payload is opaque JSONB
-- so no DDL alter and no data migration are needed.) The partial index
-- `reminders_due_pending` keeps the every-60s poll
-- (`status='pending' AND due_at < now()+interval '60s' FOR UPDATE SKIP LOCKED`)
-- cheap as `sent`/`failed`/`cancelled` rows accumulate. Applies on top of 07.

CREATE TABLE reminders (
  id              BIGSERIAL PRIMARY KEY,
  clinic_id       BIGINT NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
  session_id      BIGINT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  due_at          TIMESTAMPTZ NOT NULL,
  kind            TEXT NOT NULL,            -- 'pre_visit_24h' | 'post_visit_feedback' | 'same_day'
  payload         JSONB NOT NULL,           -- semantic: {platform_user_id, language, kind, doctor_name, slot_at, clinic_zone}
  status          TEXT NOT NULL DEFAULT 'pending',  -- 'pending' | 'sent' | 'failed' | 'cancelled'
  sent_at         TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX reminders_due_pending ON reminders(due_at) WHERE status = 'pending';
