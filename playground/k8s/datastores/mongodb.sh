#!/usr/bin/env bash
# MongoDB: a one-member replica set (FoodStack needs transactions).
source "$(dirname "$0")/../lib.sh"
network
run_container fsplay-mongodb "$MONGODB_IP" "$MONGODB_IMAGE" --replSet rs0 --bind_ip_all
wait_for "fsplay-mongodb" 60 docker exec fsplay-mongodb mongosh --quiet --eval 'db.adminCommand("ping")'
docker exec fsplay-mongodb mongosh --quiet --eval "try { rs.status() } catch (e) { rs.initiate({_id: 'rs0', members: [{_id: 0, host: '$MONGODB_IP:27017'}]}) }" > /dev/null
wait_for "fsplay-mongodb replica set" 60 docker exec fsplay-mongodb mongosh --quiet --eval 'if (!db.hello().isWritablePrimary) quit(1)'
info "MongoDB on $MONGODB_IP:27017 (replica set rs0)"
