#!/bin/bash
# One-time host provisioning: installs Docker Engine + the Compose plugin on the
# target box so scripts/deploy.sh can run. Idempotent — safe to re-run.
# Usage: ./scripts/provision-host.sh --env <staging|local>
#   local  — provisions THIS machine (no SSH).
#   staging — provisions the Alibaba box over SSH.
#
# Targets Debian/Ubuntu (apt). The staging box is Ubuntu 24.04.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/env.sh"
parse_env_or_die "$@"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
echo -e "${GREEN}=== Provisioning ${DEPLOY_ENV} (${DEPLOY_HOST}) with Docker ===${NC}"

run_remote() {
    if [ "$DEPLOY_ENV" = local ]; then bash -s; else ssh "$DEPLOY_USER@$DEPLOY_HOST" 'bash -s'; fi
}

run_remote <<'REMOTE'
set -e
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    echo "Docker + Compose already present:"
    docker --version
    docker compose version
    exit 0
fi

echo "Installing Docker Engine + Compose plugin via the official apt repository..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg

install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.asc ]; then
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
fi

. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list

apt-get update -qq
apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

systemctl enable --now docker

echo "Installed:"
docker --version
docker compose version
REMOTE

echo -e "${GREEN}✅ ${DEPLOY_HOST} provisioned. You can now run: ./scripts/deploy.sh --env ${DEPLOY_ENV} --all${NC}"
