#!/bin/bash
# Runs all db-stats queries on the selected environment's PostgreSQL via a single
# SSH connection (docker exec into symptree-postgres).
# Usage: ./scripts/db-stats.sh --env <staging|local>
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/env.sh"
parse_env_or_die "$@"

# For local, run docker exec directly; for staging, over SSH. POSTGRES_USER/DB
# default to the compose values but honour a shell override.
PG_USER="${POSTGRES_USER:-symptree_user}"
PG_DB="${POSTGRES_DB:-symptree}"
PSQL="docker exec -i symptree-postgres psql -U ${PG_USER} -d ${PG_DB}"

run() {
    if [ "$DEPLOY_ENV" = local ]; then
        eval "$PSQL"
    else
        ssh "$DEPLOY_USER@$DEPLOY_HOST" "$PSQL"
    fi
}

run <<'SQL'
-- Report anchor: day-buckets below use the UTC calendar date (DB session is Etc/UTC).
SELECT to_char(now(), 'YYYY-MM-DD HH24:MI:SS') || ' UTC' AS report_generated,
       current_date::text AS utc_today;

-- Clinics
SELECT id, slug, name, default_language, created_at::date AS created
FROM clinics
ORDER BY id;

-- Sessions created (last 7 days)
SELECT DATE(created_at) AS day, COUNT(*) AS sessions_created
FROM sessions
WHERE created_at >= CURRENT_DATE - INTERVAL '6 days'
GROUP BY DATE(created_at)
ORDER BY day;

-- Sessions by status (all-time)
SELECT status, COUNT(*) AS n
FROM sessions
GROUP BY status
ORDER BY n DESC;

-- Active sessions per clinic
SELECT c.slug, COUNT(*) AS active_sessions
FROM sessions s
JOIN clinics c ON s.clinic_id = c.id
WHERE s.status = 'active'
GROUP BY c.slug
ORDER BY active_sessions DESC;

-- Bookings created (last 7 days) by status
SELECT DATE(created_at) AS day,
  COUNT(*) FILTER (WHERE status = 'confirmed') AS confirmed,
  COUNT(*) FILTER (WHERE status = 'cancelled') AS cancelled,
  COUNT(*) FILTER (WHERE status = 'completed') AS completed
FROM bookings
WHERE created_at >= CURRENT_DATE - INTERVAL '6 days'
GROUP BY DATE(created_at)
ORDER BY day;

-- Upcoming confirmed bookings (slot in the future)
SELECT c.slug, COUNT(*) AS upcoming_confirmed
FROM bookings b
JOIN clinics c ON b.clinic_id = c.id
WHERE b.status = 'confirmed' AND b.slot_at >= now()
GROUP BY c.slug
ORDER BY upcoming_confirmed DESC;

-- Bookings by status (all-time)
SELECT status, COUNT(*) AS n
FROM bookings
GROUP BY status
ORDER BY n DESC;

-- Reminders outbox by status
SELECT status, COUNT(*) AS n
FROM reminders
GROUP BY status
ORDER BY n DESC;

-- Reminders due but still pending (potential backlog)
SELECT COUNT(*) AS pending_overdue
FROM reminders
WHERE status = 'pending' AND due_at < now();

-- Doctors per clinic
SELECT c.slug, COUNT(*) AS doctors
FROM doctors d
JOIN clinics c ON d.clinic_id = c.id
GROUP BY c.slug
ORDER BY doctors DESC;

-- Totals
SELECT
  (SELECT COUNT(*) FROM clinics)  AS clinics,
  (SELECT COUNT(*) FROM sessions) AS sessions,
  (SELECT COUNT(*) FROM bookings) AS bookings,
  (SELECT COUNT(*) FROM reminders) AS reminders,
  (SELECT COUNT(*) FROM doctors)  AS doctors;
SQL
