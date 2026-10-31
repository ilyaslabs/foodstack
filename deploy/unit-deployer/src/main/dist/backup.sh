#!/usr/bin/env bash
# Backs up MongoDB (restore.sh restores the foodstack_* databases), media and configuration into backups/<timestamp>.
# Kafka, Redis and search need no backup (deployment guide 3.6). Copy backups off the server.
source "$(dirname "$0")/lib/common.sh"
require_install_dir
set -a; source "$FOODSTACK_HOME/.env"; set +a
target="$FOODSTACK_HOME/backups/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$target"
info "MongoDB → $target/mongodb.archive.gz"
compose exec -T mongodb sh -c 'mongodump --quiet --archive --gzip -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin' \
  > "$target/mongodb.archive.gz"
info "Media → $target/storage.tar.gz"
compose exec -T storage tar -C /data -czf - . > "$target/storage.tar.gz"
info "Configuration → $target/config.tar.gz"
tar -C "$FOODSTACK_HOME" -czf "$target/config.tar.gz" .env config VERSION
chmod -R go-rwx "$target"
info "Backup done: $target"
