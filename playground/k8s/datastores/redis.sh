#!/usr/bin/env bash
# Redis: caches, limits, live updates.
source "$(dirname "$0")/../lib.sh"
network
run_container fsplay-redis "$REDIS_IP" "$REDIS_IMAGE"
wait_for "fsplay-redis" 30 docker exec fsplay-redis redis-cli ping
info "Redis on $REDIS_IP:6379"
