#!/usr/bin/env bash
# Removes this FoodStack install: containers, data volumes and this folder's runtime files.
# Backups in backups/ are kept. Asks first unless run with --yes.
source "$(dirname "$0")/lib/common.sh"
require_install_dir
if [[ "${1:-}" != --yes ]]; then
  printf 'This deletes all FoodStack data on this machine (orders, users, media). Backups in %s/backups are kept.\nType "delete" to continue: ' "$FOODSTACK_HOME"
  read -r answer
  [[ "$answer" == delete ]] || fail "Cancelled."
fi
info "Removing containers and data volumes"
compose --profile search down -v --remove-orphans
rm -rf "${FOODSTACK_HOME:?}"/{.env,.installed,.previous,app,config,lib,logs,docker-compose.yml,.env.example,VERSION,README.md}
rm -f "$FOODSTACK_HOME"/{start,stop,restart,status,logs,backup,restore,upgrade}.sh
if [[ -f /etc/systemd/system/foodstack.service ]]; then
  info "Removing the systemd unit (needs root)"
  systemctl disable --now foodstack.service > /dev/null 2>&1 || true
  rm -f /etc/systemd/system/foodstack.service && systemctl daemon-reload || warn "Run with sudo to remove the systemd unit."
fi
info "Uninstalled. Delete $FOODSTACK_HOME yourself once you no longer need its backups."
rm -f "$0"
