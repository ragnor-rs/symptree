#!/bin/bash
# Single source of truth for per-environment infrastructure values.
# Source this file, then call: resolve_env <staging|local>
# On success it sets: DEPLOY_ENV, DEPLOY_HOST, DEPLOY_USER
#
# The staging host has no default — STAGING_HOST must be set. Deployment targets
# are environment-specific, so this file carries no infrastructure addresses.

resolve_env() {
    case "${1:-}" in
        staging)
            # Staging box — the whole stack (postgres + core + messaging +
            # admin-panel) runs here. Plain http on the public IP (no TLS/domain yet).
            DEPLOY_ENV="staging"
            if [ -z "${STAGING_HOST:-}" ]; then
                echo "Error: STAGING_HOST must be set (the staging host address)." >&2
                return 2
            fi
            DEPLOY_HOST="$STAGING_HOST"
            ;;
        local)
            # Local Docker on this machine. No SSH/secret-fetch — deploy.sh runs
            # compose directly and reads secrets from the dev's shell env.
            DEPLOY_ENV="local"
            DEPLOY_HOST="localhost"
            ;;
        *)
            echo "Error: --env must be 'staging' or 'local' (got: '${1:-<empty>}')" >&2
            return 2
            ;;
    esac
    DEPLOY_USER="${DEPLOY_USER:-root}"
}

# Parse an optional `--env <staging|local>` out of "$@" and resolve it. For the
# monitor callers (db-stats); errors on any extra arg. Defaults to
# staging when --env is omitted (the monitors default to the only remote env).
parse_env_or_die() {
    local env="staging"
    while [ $# -gt 0 ]; do
        case "$1" in
            --env) env="$2"; shift 2 ;;
            --env=*) env="${1#*=}"; shift ;;
            *) echo "Unknown arg: $1 (only --env <staging|local> is supported)" >&2; exit 2 ;;
        esac
    done
    resolve_env "$env" || exit 2
}
