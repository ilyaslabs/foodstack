#!/usr/bin/env bash
# Restores a backup made by backup.sh: ./restore.sh backups/<timestamp>
source "$(dirname "$0")/lib/common.sh"
require_install_dir
backup=${1:?usage: restore.sh <backup-dir>}
[[ -f "$backup/mongodb.archive.gz" ]] || fail "$backup has no mongodb.archive.gz"
info "Stopping the application"
compose stop proxy web backoffice server
info "Restoring MongoDB"
compose exec -T mongodb sh -c 'mongorestore --quiet --archive --gzip --drop -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --nsInclude "foodstack_*.*"' \
  < "$backup/mongodb.archive.gz"
info "Restoring media"
compose exec -T storage sh -c 'find /data -mindepth 1 -delete && tar -C /data -xzf -' < "$backup/storage.tar.gz"
compose restart storage
info "Starting"
compose up -d
info "Restore done. Search indices rebuild on start once the search module is installed."
