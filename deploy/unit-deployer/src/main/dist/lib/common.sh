#!/usr/bin/env bash
# Shared helpers for the FoodStack unit deployer scripts.
set -euo pipefail

FOODSTACK_HOME="${FOODSTACK_HOME:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# Whether this install keeps photos on this machine: --local trials, and installs made before
# real buckets were required (handbook guide 12 moves them).
trial_storage() {
  grep -qx 'FOODSTACK_TRIAL_STORAGE=true' "$FOODSTACK_HOME/.env" 2> /dev/null
}

compose() {
  local files=(-f "$FOODSTACK_HOME/docker-compose.yml")
  if trial_storage; then files+=(-f "$FOODSTACK_HOME/docker-compose.trial-storage.yml"); fi
  docker compose --project-directory "$FOODSTACK_HOME" --env-file "$FOODSTACK_HOME/.env" "${files[@]}" "$@"
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

# The proxy's route for a trial's bucket paths, or nothing for a real install.
write_storage_route() {  # write_storage_route <install dir> <true|false>
  if [[ $2 == true ]]; then
    cat > "$1/config/storage.caddy" << 'ROUTE'
# Trial storage on this machine: presigned uploads and public images go to it. The Host header is
# passed unchanged, so upload signatures made for this hostname stay valid.
@storage path /foodstack-public/* /foodstack-private/*
handle @storage {
	reverse_proxy storage:9000
}
ROUTE
  else
    echo "# Photos and files are in your buckets (handbook guide 12); browsers reach them directly." > "$1/config/storage.caddy"
  fi
}

datastores=(mongodb redis kafka)
if trial_storage; then datastores+=(storage); fi
apps=(server web backoffice proxy)
