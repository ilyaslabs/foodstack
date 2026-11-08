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
if trial_storage && [[ -f "$backup/storage.tar.gz" ]]; then
  info "Restoring media"
  compose exec -T storage sh -c 'find /data -mindepth 1 -delete && tar -C /data -xzf -' < "$backup/storage.tar.gz"
  compose restart storage
fi
# Redis and the search index only hold copies of what is in MongoDB: clear them so nothing from
# after the backup survives. The server recreates the index and refills it on start.
info "Clearing caches and the search index"
compose exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHALL' > /dev/null
compose exec -T elasticsearch sh -c 'es="curl -fsS -u elastic:$ELASTIC_PASSWORD http://localhost:9200"
  for index in $($es"/_cat/indices/foodstack_search_stores_v*?h=index"); do $es"/$index" -X DELETE > /dev/null; done'
info "Starting"
compose up -d
info "Restore done. Store search fills again within a few minutes of the server starting."
