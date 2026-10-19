-- 02-migrate-algorithms.sql
-- M2 / FR-KT4697 — algorithms full surface.
--
-- The shell `algorithms` table created by 01-migrate-clinics.sql carries only
-- (id, algorithm_key, is_active, created_at) with UNIQUE(algorithm_key).
-- This migration grows it to the surface defined in technical-spec.md § 2.1:
--   - version  TEXT NOT NULL
--   - metadata JSONB NOT NULL
--   - tree     JSONB NOT NULL
-- and swaps the unique constraint to UNIQUE(algorithm_key, version) so the
-- same algorithm can have multiple versioned rows side-by-side.
--
-- Defaults on the new columns are applied during ADD COLUMN purely to keep the
-- ALTER valid even if the table held rows; they are dropped immediately after
-- so future inserts must supply explicit values.

ALTER TABLE algorithms DROP CONSTRAINT algorithms_algorithm_key_key;

ALTER TABLE algorithms ADD COLUMN version  TEXT  NOT NULL DEFAULT '1.0.0';
ALTER TABLE algorithms ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE algorithms ADD COLUMN tree     JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE algorithms ALTER COLUMN version  DROP DEFAULT;
ALTER TABLE algorithms ALTER COLUMN metadata DROP DEFAULT;
ALTER TABLE algorithms ALTER COLUMN tree     DROP DEFAULT;

ALTER TABLE algorithms ADD CONSTRAINT algorithms_key_version_uniq UNIQUE (algorithm_key, version);
