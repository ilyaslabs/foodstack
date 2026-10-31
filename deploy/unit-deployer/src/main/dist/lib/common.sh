#!/usr/bin/env bash
# Shared helpers for the FoodStack unit deployer scripts.
set -euo pipefail

FOODSTACK_HOME="${FOODSTACK_HOME:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

compose() {
  docker compose --project-directory "$FOODSTACK_HOME" --env-file "$FOODSTACK_HOME/.env" \
    -f "$FOODSTACK_HOME/docker-compose.yml" "$@"
}

require_install_dir() {
  [[ -f "$FOODSTACK_HOME/.env" && -f "$FOODSTACK_HOME/docker-compose.yml" ]] \
    || fail "Run this from a FoodStack install directory (created by install.sh)."
}

# Waits until a compose service reports healthy.
wait_healthy() {
  local service=$1 timeout=${2:-300} waited=0 id status
  while (( waited < timeout )); do
    id=$(compose ps -q "$service" 2>/dev/null || true)
    if [[ -n "$id" ]]; then
      status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")
      [[ "$status" == healthy ]] && return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  compose logs --tail 50 "$service" >&2 || true
  fail "$service did not become healthy within ${timeout}s"
}

datastores=(mongodb redis kafka storage)
apps=(server web backoffice proxy)
