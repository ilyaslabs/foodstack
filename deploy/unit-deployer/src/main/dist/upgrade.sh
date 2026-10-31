#!/usr/bin/env bash
# Upgrades to a new release: ./upgrade.sh unit-deployer-<version>.zip
# Rolls back to the files kept before the last upgrade: ./upgrade.sh --rollback
source "$(dirname "$0")/lib/common.sh"
require_install_dir
previous="$FOODSTACK_HOME/.previous"

if [[ "${1:-}" == --rollback ]]; then
  [[ -d "$previous" ]] || fail "No previous version to roll back to."
  info "Rolling back to $(cat "$previous/VERSION")"
  compose stop proxy web backoffice server
  cp -a "$previous/." "$FOODSTACK_HOME/"
  compose up -d
  info "Rolled back. Restore the pre-upgrade backup only if the release notes say so."
  exit 0
fi

zip=${1:?usage: upgrade.sh <unit-deployer.zip> | --rollback}
command -v unzip > /dev/null || fail "unzip is required"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
unzip -q "$zip" -d "$work"
release=$(find "$work" -mindepth 1 -maxdepth 1 -type d | head -1)
[[ -f "$release/app/foodstack-server.jar" ]] || fail "$zip is not a FoodStack unit deployer package"

info "Backing up first"
"$FOODSTACK_HOME/backup.sh"
rm -rf "$previous"; mkdir -p "$previous"
cp -a "$FOODSTACK_HOME/app" "$FOODSTACK_HOME/docker-compose.yml" "$FOODSTACK_HOME/VERSION" "$FOODSTACK_HOME/lib" "$previous/"
cp -a "$FOODSTACK_HOME"/*.sh "$previous/"

info "Installing $(cat "$release/VERSION")"
rm -rf "${FOODSTACK_HOME:?}/app"
cp -a "$release/app" "$release/docker-compose.yml" "$release/VERSION" "$release/lib" "$FOODSTACK_HOME/"
cp -a "$release"/*.sh "$FOODSTACK_HOME/"
cp -a "$release/config/Caddyfile" "$release/config/backoffice.conf.template" "$FOODSTACK_HOME/config/"

info "Running migrations"
compose stop proxy web backoffice server
compose run --rm --no-deps server java -jar /app/foodstack-server.jar --foodstack.migrate-only=true
compose up -d
info "Upgraded to $(cat "$FOODSTACK_HOME/VERSION")"
