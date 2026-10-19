-- 03-migrate-sessions.sql
-- M2 / FR-PWVTT4 — bot sessions table.
--
-- One row per session (unique per platform x platform_user_id x clinic_id x status).
-- `history` JSONB array carries per-turn records: {node_id, user_text_encrypted, llm_intent, picked_option, ts}.

CREATE TABLE sessions (
    id                     BIGSERIAL PRIMARY KEY,
    clinic_id              BIGINT NOT NULL REFERENCES clinics(id) ON DELETE RESTRICT,
    platform               TEXT NOT NULL,
    platform_user_id       TEXT NOT NULL,
    language               TEXT NOT NULL,
    current_algorithm_id   BIGINT REFERENCES algorithms(id),
    current_node_id        TEXT,
    history                JSONB NOT NULL DEFAULT '[]'::jsonb,
    status                 TEXT NOT NULL DEFAULT 'active',
    retention_anonymize_at TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (platform, platform_user_id, clinic_id, status)
);
CREATE INDEX sessions_clinic_active ON sessions(clinic_id) WHERE status = 'active';
