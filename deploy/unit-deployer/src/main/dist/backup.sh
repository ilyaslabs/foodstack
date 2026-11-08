#!/usr/bin/env bash
# Backs up MongoDB (restore.sh restores the foodstack_* databases) and configuration into backups/<timestamp>;
# on a trial also the photos kept on this machine.
# Kafka, Redis and search need no backup (deployment guide 3.6). Copy backups off the server.
source "$(dirname "$0")/lib/common.sh"
require_install_dir
target="$FOODSTACK_HOME/backups/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$target"
info "MongoDB → $target/mongodb.archive.gz"
compose exec -T mongodb sh -c 'mongodump --quiet --archive --gzip -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin' \
  > "$target/mongodb.archive.gz"
# Photos are in your buckets, protected by the provider (turn on versioning, handbook guide 20);
# only a trial keeps them on this machine.
if trial_storage; then
  info "Media → $target/storage.tar.gz"
  compose exec -T storage tar -C /data -czf - . > "$target/storage.tar.gz"
fi
info "Configuration → $target/config.tar.gz"
tar -C "$FOODSTACK_HOME" -czf "$target/config.tar.gz" .env config VERSION
chmod -R go-rwx "$target"
info "Backup done: $target"
