#!/usr/bin/env bash
# Elasticsearch: store search, one node, small heap.
source "$(dirname "$0")/../lib.sh"
network
run_container fsplay-elasticsearch "$ELASTICSEARCH_IP" \
  -e discovery.type=single-node -e xpack.security.enabled=true -e xpack.security.http.ssl.enabled=false \
  -e node.store.allow_mmap=false -e cluster.routing.allocation.disk.threshold_enabled=false \
  -e ELASTIC_PASSWORD="$ELASTICSEARCH_PASSWORD" -e ES_JAVA_OPTS="-Xms512m -Xmx512m" \
  "$ELASTICSEARCH_IMAGE"
wait_for "fsplay-elasticsearch" 180 docker exec fsplay-elasticsearch curl -fsS -u "elastic:$ELASTICSEARCH_PASSWORD" http://localhost:9200/_cluster/health
info "Elasticsearch on http://$ELASTICSEARCH_IP:9200 (elastic / $ELASTICSEARCH_PASSWORD)"
