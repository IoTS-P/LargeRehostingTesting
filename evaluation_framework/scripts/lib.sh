#!/usr/bin/env bash
# Shared helpers for every script in this directory.
# All scripts can be run from the host (they drive the container through
# docker compose) or from inside the container (no docker needed).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/evaluation_framework/docker/docker-compose.yml"
SERVICE=akiba
CONTAINER=largerehosting_akiba
FRAMEWORK=/home/akiba/akiba_framework
PIPELINES_DIR=/data/pipelines
SAMPLES_DIR=/data/samples
RESULTS_DIR=/data/results
DB_INSTANCE=akiba-instance

c_red()   { printf '\033[31m%s\033[0m\n' "$*"; }
c_green() { printf '\033[32m%s\033[0m\n' "$*"; }
c_blue()  { printf '\033[34m%s\033[0m\n' "$*"; }
die()     { c_red "ERROR: $*"; exit 1; }

# Are we inside the pipeline container?
in_container() { [ -x "$FRAMEWORK/bin/akiba_framework" ] && [ -d /home/akiba/akiba_db_daemon ]; }

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# Run a shell snippet inside the container (host mode only).
inside() {
    compose exec -T "$SERVICE" bash -lc "$1" \
      || die "command failed inside the container: $1"
}

container_running() {
    docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"
}

require_container() {
    container_running || die "container '$CONTAINER' is not running — start it with scripts/up.sh"
}