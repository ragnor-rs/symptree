#!/bin/bash

# symptree Deployment Script
# Deploys symptree (postgres + core + messaging + admin-panel) to a remote host, or
# runs the same compose stack locally. Provider-agnostic: works against any Linux
# host reachable over SSH with Docker + Docker Compose (auto-detects v1
# `docker-compose` or v2 `docker compose`).
#
# Required: --env <staging|local>
#   staging — alibaba box: the whole stack, plain http on the public IP
#   local   — this machine: compose runs directly, secrets read from the shell env
#
# Secrets: for --env staging the script sources scripts/secrets.staging.env (a
# gitignored file — copy scripts/secrets.staging.env.example and fill it in) before
# writing the remote .env. For --env local, export the secrets in your shell first
# (or `source .env.local`).

# Resolve script dir so we can source the shared env resolver.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/env.sh
source "${SCRIPT_DIR}/lib/env.sh"

# Parse command line arguments
ENVIRONMENT=""
DEPLOY_POSTGRES=false
DEPLOY_CORE=false
DEPLOY_MESSAGING=false
DEPLOY_ADMIN_PANEL=false

show_usage() {
    echo "Usage: $0 --env <staging|local> [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --env <staging|local>  REQUIRED — target environment"
    echo "  --postgres             Deploy only PostgreSQL"
    echo "  --core                 Deploy only the core backend"
    echo "  --messaging            Deploy only the messaging (Telegram) bot"
    echo "  --admin-panel          Deploy only the admin panel (SPA + nginx)"
    echo "  --all                  Deploy all services (default when no service flag given)"
    echo "  -h, --help             Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 --env staging --all               # Deploy the whole stack to staging"
    echo "  $0 --env staging --messaging         # Redeploy just the bot (e.g. after CLINIC_SLUG change)"
    echo "  $0 --env local --all                 # Run the whole stack locally via compose"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --env)          ENVIRONMENT="$2"; shift 2 ;;
        --env=*)        ENVIRONMENT="${1#*=}"; shift ;;
        --postgres)     DEPLOY_POSTGRES=true; shift ;;
        --core)         DEPLOY_CORE=true; shift ;;
        --messaging)    DEPLOY_MESSAGING=true; shift ;;
        --admin-panel)  DEPLOY_ADMIN_PANEL=true; shift ;;
        --all)
            DEPLOY_POSTGRES=true
            DEPLOY_CORE=true
            DEPLOY_MESSAGING=true
            DEPLOY_ADMIN_PANEL=true
            shift
            ;;
        -h|--help)      show_usage; exit 0 ;;
        *) echo "Unknown option: $1"; show_usage; exit 1 ;;
    esac
done

# If no service flags specified, default to --all
if [ "$DEPLOY_POSTGRES" = false ] && [ "$DEPLOY_CORE" = false ] && [ "$DEPLOY_MESSAGING" = false ] && [ "$DEPLOY_ADMIN_PANEL" = false ]; then
    DEPLOY_POSTGRES=true
    DEPLOY_CORE=true
    DEPLOY_MESSAGING=true
    DEPLOY_ADMIN_PANEL=true
fi

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

REMOTE_DIR="/opt/symptree"

# Capture an explicit DEPLOY_HOST override before resolve_env (which sets DEPLOY_HOST
# from the per-env default). IPs live in lib/env.sh — nothing has to be exported.
EXPLICIT_HOST="${DEPLOY_HOST:-}"

# Resolve the deploy target host (validates --env too).
if ! resolve_env "$ENVIRONMENT"; then
    echo -e "${RED}Error: --env must be 'staging' or 'local' (got: '${ENVIRONMENT:-<empty>}')${NC}"
    echo "Example: $0 --env staging --all"
    exit 1
fi
REMOTE_USER="$DEPLOY_USER"
REMOTE_HOST="${EXPLICIT_HOST:-$DEPLOY_HOST}"

# Execution abstraction. 'local' runs Docker Compose directly on this machine — no
# SSH, no file transfer, the .env + build context live in the repo root. Staging runs
# the exact same steps over SSH to $REMOTE_HOST. ssh_exec runs one command; $SSH_BASH
# fronts the bash -s heredocs.
IS_LOCAL=false
[ "$ENVIRONMENT" = local ] && IS_LOCAL=true
if [ "$IS_LOCAL" = true ]; then
    REMOTE_DIR="$(dirname "$SCRIPT_DIR")"   # repo root — compose file + build context already here
    SSH_BASH="bash -s"
    ssh_exec() { bash -c "$1"; }
else
    SSH_BASH="ssh ${REMOTE_USER}@${REMOTE_HOST} bash -s"
    ssh_exec() { ssh "${REMOTE_USER}@${REMOTE_HOST}" "$1"; }
fi

# For staging, pull secrets from the gitignored secrets file if present (shell env
# still wins — it's sourced only to fill vars you haven't already exported).
SECRETS_FILE="${SCRIPT_DIR}/secrets.staging.env"
if [ "$IS_LOCAL" = false ] && [ -f "$SECRETS_FILE" ]; then
    echo "Sourcing secrets from ${SECRETS_FILE}"
    set -a
    # shellcheck disable=SC1090
    source "$SECRETS_FILE"
    set +a
fi

# Per-env non-secret values. CLINIC_SLUG / OPENAI_MODEL may be overridden by the
# secrets file or shell env; these are the defaults.
POSTGRES_DB="${POSTGRES_DB:-symptree}"
POSTGRES_USER="${POSTGRES_USER:-symptree_user}"
OPENAI_MODEL="${OPENAI_MODEL:-gpt-4o-2024-08-06}"
# Admin-panel bearer-token TTL. Default 7 days (10080 min) so the persisted SPA
# session isn't invalidated server-side too soon; override via env/secrets file.
ADMIN_TOKEN_TTL_MINUTES="${ADMIN_TOKEN_TTL_MINUTES:-10080}"

echo -e "${GREEN}=== symptree Deployment Script (env: ${ENVIRONMENT}) ===${NC}"
echo "Remote Host: $REMOTE_HOST"
echo "Remote User: $REMOTE_USER"
echo "Remote Directory: $REMOTE_DIR"

# Display deployment mode
SERVICES_DISPLAY=()
[ "$DEPLOY_POSTGRES" = true ] && SERVICES_DISPLAY+=("postgres")
[ "$DEPLOY_CORE" = true ] && SERVICES_DISPLAY+=("core")
[ "$DEPLOY_MESSAGING" = true ] && SERVICES_DISPLAY+=("messaging")
[ "$DEPLOY_ADMIN_PANEL" = true ] && SERVICES_DISPLAY+=("admin-panel")
echo "Deploy Mode: ${SERVICES_DISPLAY[*]}"
echo ""

# Check required environment variables. core needs the app-tier secrets; messaging
# needs the bot secrets. postgres/admin-panel alone need only POSTGRES_PASSWORD.
REQUIRED_VARS=("POSTGRES_PASSWORD")
if [ "$DEPLOY_CORE" = true ]; then
    REQUIRED_VARS+=(
        "PLATFORM_ADMIN_API_KEY"
        "BOT_INGRESS_SHARED_SECRET"
        "BOT_INTERNAL_SHARED_SECRET"
        "OPENAI_API_KEY"
        "SYMPTREE_PII_KEY"
        "ADMIN_TOKEN_SECRET"
    )
fi
if [ "$DEPLOY_MESSAGING" = true ]; then
    REQUIRED_VARS+=(
        "BOT_INGRESS_SHARED_SECRET"
        "BOT_INTERNAL_SHARED_SECRET"
        "TELEGRAM_BOT_TOKEN"
        "CLINIC_SLUG"
    )
fi

missing_vars=()
for var in "${REQUIRED_VARS[@]}"; do
    [ -z "${!var}" ] && missing_vars+=("$var")
done
# De-dup (shared secrets appear under both core + messaging).
missing_vars=($(printf '%s\n' "${missing_vars[@]}" | sort -u))

if [ ${#missing_vars[@]} -gt 0 ]; then
    echo -e "${RED}Error: the following required environment variables are not set:${NC}"
    printf "  - %s\n" "${missing_vars[@]}"
    echo ""
    if [ "$IS_LOCAL" = true ]; then
        echo "For --env local, export them (or: source .env.local) before deploying."
    else
        echo "For --env staging, fill in ${SECRETS_FILE}"
        echo "  cp ${SCRIPT_DIR}/secrets.staging.env.example ${SECRETS_FILE}   # then edit"
    fi
    exit 1
fi
echo -e "${GREEN}✅ All required environment variables are set${NC}"
echo ""

# ── Step 1: Build artifacts locally ─────────────────────────────────────────
# JVM services ship prebuilt fat jars; the admin-panel ships a prebuilt Vite bundle
# (dist/). Everything is built here on the dev machine so no source/toolchain lands
# in an image layer or on the deploy host.
echo -e "${YELLOW}📦 Step 1: Building artifacts...${NC}"
BUILD_MODULES=()
[ "$DEPLOY_CORE" = true ] && BUILD_MODULES+=(":core:shadowJar")
[ "$DEPLOY_MESSAGING" = true ] && BUILD_MODULES+=(":messaging:shadowJar")

if [ ${#BUILD_MODULES[@]} -gt 0 ]; then
    echo "Building JVM: ${BUILD_MODULES[*]}"
    ./gradlew clean "${BUILD_MODULES[@]}" || { echo -e "${RED}Error: Gradle build failed${NC}"; exit 1; }
else
    echo "No JVM services to build"
fi

# admin-panel: compile the SPA to admin-panel/dist. The image + host only ever see
# this prebuilt bundle (see admin-panel/Dockerfile). npm ci keeps it lockfile-exact.
if [ "$DEPLOY_ADMIN_PANEL" = true ]; then
    echo "Building admin-panel SPA (Vite → dist)..."
    ( cd admin-panel && npm ci && npm run build ) \
        || { echo -e "${RED}Error: admin-panel build failed${NC}"; exit 1; }
    [ -d "admin-panel/dist" ] || { echo -e "${RED}Error: admin-panel/dist not found after build${NC}"; exit 1; }
fi

if [ "$DEPLOY_CORE" = true ] && [ ! -f "core/build/libs/core.jar" ]; then
    echo -e "${RED}Error: core.jar not found after build${NC}"; exit 1
fi
if [ "$DEPLOY_MESSAGING" = true ] && [ ! -f "messaging/build/libs/messaging.jar" ]; then
    echo -e "${RED}Error: messaging.jar not found after build${NC}"; exit 1
fi
if [ ${#BUILD_MODULES[@]} -gt 0 ]; then
    echo -e "${GREEN}✅ JAR files built${NC}"
    [ "$DEPLOY_CORE" = true ] && echo "  core.jar: $(du -h core/build/libs/core.jar | cut -f1)"
    [ "$DEPLOY_MESSAGING" = true ] && echo "  messaging.jar: $(du -h messaging/build/libs/messaging.jar | cut -f1)"
fi
echo ""

# ── Step 2: Ensure deploy directory exists (no-op locally — it's the repo root) ──
echo -e "${YELLOW}📁 Step 2: Preparing deploy directory (${REMOTE_DIR})...${NC}"
ssh_exec "mkdir -p ${REMOTE_DIR}" || { echo -e "${RED}Error: Failed to prepare deploy directory${NC}"; exit 1; }
echo -e "${GREEN}✅ Deploy directory ready${NC}"
echo ""

# ── Step 3: Transfer project files (skipped for local — files already in place) ──
echo -e "${YELLOW}📤 Step 3: Transferring project files...${NC}"
if [ "$IS_LOCAL" = true ]; then
    echo "Local deploy — using repo files in place; no archive/transfer needed."
else
    TEMP_TAR="symptree-deploy-$(date +%s).tar.gz"
    echo "Creating deployment archive: $TEMP_TAR"

    # docker-compose.yml + the database migrations (core bind-mounts them) always ship.
    TAR_FILES="docker-compose.yml database"
    [ "$DEPLOY_CORE" = true ]        && TAR_FILES="$TAR_FILES core/Dockerfile core/build/libs/core.jar"
    [ "$DEPLOY_MESSAGING" = true ]   && TAR_FILES="$TAR_FILES messaging/Dockerfile messaging/build/libs/messaging.jar"
    [ "$DEPLOY_ADMIN_PANEL" = true ] && TAR_FILES="$TAR_FILES admin-panel/Dockerfile admin-panel/nginx.conf admin-panel/dist"

    # COPYFILE_DISABLE=1 + --exclude='._*' stop macOS tar from emitting AppleDouble
    # (`._*`) extended-attribute forks. Without this, `._NN-migrate-*.sql` files land
    # in the remote database/ dir and MigrationRunner (which globs *.sql) tries to
    # execute the binary junk → "Unterminated identifier" on startup.
    COPYFILE_DISABLE=1 tar --exclude='._*' -czf "$TEMP_TAR" $TAR_FILES \
        || { echo -e "${RED}Error: Failed to create archive${NC}"; exit 1; }
    echo "Archive created: $(du -h "$TEMP_TAR" | cut -f1)"

    echo "Transferring archive to remote server..."
    rsync -az --progress "$TEMP_TAR" "${REMOTE_USER}@${REMOTE_HOST}:/tmp/" \
        || { echo -e "${RED}Error: Failed to transfer archive${NC}"; rm -f "$TEMP_TAR"; exit 1; }

    # The whole database/ dir is bind-mounted into core; wipe it first so a migration
    # deleted from the repo can't linger on the box (tar never removes stale files).
    echo "Extracting archive on remote server..."
    ssh "${REMOTE_USER}@${REMOTE_HOST}" "rm -rf ${REMOTE_DIR}/database"
    ssh "${REMOTE_USER}@${REMOTE_HOST}" "cd ${REMOTE_DIR} && tar -xzf /tmp/$TEMP_TAR && rm -f /tmp/$TEMP_TAR" \
        || { echo -e "${RED}Error: Failed to extract archive on remote server${NC}"; rm -f "$TEMP_TAR"; exit 1; }
    rm -f "$TEMP_TAR"
    echo -e "${GREEN}✅ Files transferred successfully${NC}"
fi
echo ""

# ── Step 4: Write the .env (single source of truth) ──────────────────────────
echo -e "${YELLOW}🔐 Step 4: Setting up environment variables on remote server...${NC}"
ENV_CONTENT="# symptree Environment Variables — ${ENVIRONMENT}
# THIS FILE IS THE SINGLE SOURCE OF TRUTH for every var docker-compose.yml references.
# docker-compose has zero \${VAR:-default} fallbacks — if it isn't here, the container
# gets an empty string. To change a value, edit scripts/secrets.staging.env (or the
# deploy env) and redeploy.

# Database
POSTGRES_DB=${POSTGRES_DB}
POSTGRES_USER=${POSTGRES_USER}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}

# core (backend) secrets + config
PLATFORM_ADMIN_API_KEY=${PLATFORM_ADMIN_API_KEY}
BOT_INGRESS_SHARED_SECRET=${BOT_INGRESS_SHARED_SECRET}
BOT_INTERNAL_SHARED_SECRET=${BOT_INTERNAL_SHARED_SECRET}
OPENAI_API_KEY=${OPENAI_API_KEY}
OPENAI_MODEL=${OPENAI_MODEL}
SYMPTREE_PII_KEY=${SYMPTREE_PII_KEY}
ADMIN_TOKEN_SECRET=${ADMIN_TOKEN_SECRET}
ADMIN_TOKEN_TTL_MINUTES=${ADMIN_TOKEN_TTL_MINUTES}

# messaging (Telegram bot) — single-tenant per JVM, points at one clinic
CLINIC_SLUG=${CLINIC_SLUG}
TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
"

if [ "$IS_LOCAL" = true ]; then
    printf '%s\n' "$ENV_CONTENT" > "${REMOTE_DIR}/.env"
else
    echo "$ENV_CONTENT" | ssh "${REMOTE_USER}@${REMOTE_HOST}" "cat > ${REMOTE_DIR}/.env"
fi
[ $? -eq 0 ] || { echo -e "${RED}Error: Failed to create .env file${NC}"; exit 1; }
echo -e "${GREEN}✅ Environment variables configured${NC}"
echo ""

# ── Step 5: Deploy with Docker Compose ───────────────────────────────────────
echo -e "${YELLOW}🚀 Step 5: Deploying services with Docker Compose...${NC}"
$SSH_BASH << ENDSSH
set -e
cd ${REMOTE_DIR}

DEPLOY_POSTGRES=$DEPLOY_POSTGRES
DEPLOY_CORE=$DEPLOY_CORE
DEPLOY_MESSAGING=$DEPLOY_MESSAGING
DEPLOY_ADMIN_PANEL=$DEPLOY_ADMIN_PANEL

# Auto-detect Docker Compose: v1 binary 'docker-compose' or v2 plugin 'docker compose'.
if command -v docker-compose >/dev/null 2>&1; then DC="docker-compose"; else DC="docker compose"; fi

[ -f "docker-compose.yml" ] || { echo "ERROR: docker-compose.yml not found"; exit 1; }
if [ "\$DEPLOY_CORE" = true ]; then
    [ -f "database/01-migrate-clinics.sql" ] || { echo "ERROR: database/ migrations not found"; exit 1; }
    [ -f "core/build/libs/core.jar" ] || { echo "ERROR: core.jar not found"; exit 1; }
fi
[ "\$DEPLOY_MESSAGING" = true ] && { [ -f "messaging/build/libs/messaging.jar" ] || { echo "ERROR: messaging.jar not found"; exit 1; }; }
if [ "\$DEPLOY_ADMIN_PANEL" = true ]; then
    [ -f "admin-panel/Dockerfile" ] || { echo "ERROR: admin-panel/Dockerfile not found"; exit 1; }
    [ -f "admin-panel/dist/index.html" ] || { echo "ERROR: admin-panel/dist bundle not found"; exit 1; }
fi

echo "Using compose command: \$DC"

SERVICES=""
[ "\$DEPLOY_POSTGRES" = true ] && SERVICES="\${SERVICES} postgres"
[ "\$DEPLOY_CORE" = true ] && SERVICES="\${SERVICES} core"
[ "\$DEPLOY_MESSAGING" = true ] && SERVICES="\${SERVICES} messaging"
[ "\$DEPLOY_ADMIN_PANEL" = true ] && SERVICES="\${SERVICES} admin-panel"

# Stop the affected containers (full 'down' only when redeploying everything).
if [ "\$DEPLOY_POSTGRES" = true ] && [ "\$DEPLOY_CORE" = true ] && [ "\$DEPLOY_MESSAGING" = true ] && [ "\$DEPLOY_ADMIN_PANEL" = true ]; then
    \$DC down || true
else
    \$DC stop \$SERVICES || true
fi

echo "Building Docker images..."
\$DC build \$SERVICES

echo "Starting services..."
\$DC up -d \$SERVICES

sleep 10
echo ""
echo "=== Container Status ==="
\$DC ps
ENDSSH

[ $? -eq 0 ] || { echo -e "${RED}Error: Deployment failed${NC}"; exit 1; }
echo -e "${GREEN}✅ Services deployed${NC}"
echo ""

# ── Step 6: Verify deployment ────────────────────────────────────────────────
echo -e "${YELLOW}🔍 Step 6: Verifying deployment...${NC}"
$SSH_BASH << ENDSSH
cd ${REMOTE_DIR}
if command -v docker-compose >/dev/null 2>&1; then DC="docker-compose"; else DC="docker compose"; fi

echo ""
echo "=== Running Containers ==="
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

for svc in postgres core messaging admin-panel; do
    case "\$svc" in
        postgres)    [ "$DEPLOY_POSTGRES" = true ]    || continue ;;
        core)        [ "$DEPLOY_CORE" = true ]        || continue ;;
        messaging)   [ "$DEPLOY_MESSAGING" = true ]   || continue ;;
        admin-panel) [ "$DEPLOY_ADMIN_PANEL" = true ] || continue ;;
    esac
    echo ""
    echo "=== Recent \$svc logs ==="
    \$DC logs --tail=20 "\$svc"
done
ENDSSH

echo ""
echo -e "${GREEN}🎉 Deployment completed!${NC}"
echo ""
if [ "$IS_LOCAL" = true ]; then
    echo "Local: admin panel http://localhost/   ·   core http://localhost:8080/health"
else
    echo "Staging: admin panel http://${REMOTE_HOST}/   ·   core http://${REMOTE_HOST}:8080/health"
fi
echo ""
echo "📊 Useful commands (compose = docker-compose or 'docker compose'):"
echo "  Logs:    ssh ${REMOTE_USER}@${REMOTE_HOST} 'cd ${REMOTE_DIR} && docker compose logs -f'"
echo "  Bot log: ssh ${REMOTE_USER}@${REMOTE_HOST} 'cd ${REMOTE_DIR} && docker compose logs -f messaging'"
echo "  Restart: ssh ${REMOTE_USER}@${REMOTE_HOST} 'cd ${REMOTE_DIR} && docker compose restart'"
echo "  Status:  ssh ${REMOTE_USER}@${REMOTE_HOST} 'cd ${REMOTE_DIR} && docker compose ps'"
echo ""
