#!/bin/bash
# Starts a two-shard MongoDB cluster inside one container: a config server replica set, two
# single-member shard replica sets and a mongos router on 27017. Test-only (TDD 6.3).
set -e
mkdir -p /data/cfg /data/s1 /data/s2 /data/logs
common="--bind_ip_all --fork --wiredTigerCacheSizeGB 0.25"
mongod --configsvr --replSet cfg --port 27019 --dbpath /data/cfg --logpath /data/logs/cfg.log $common
mongod --shardsvr --replSet s1 --port 27018 --dbpath /data/s1 --logpath /data/logs/s1.log $common
mongod --shardsvr --replSet s2 --port 27020 --dbpath /data/s2 --logpath /data/logs/s2.log $common

init() {
  mongosh --quiet --port "$1" --eval "rs.initiate($2)" >/dev/null
  until mongosh --quiet --port "$1" --eval 'db.hello().isWritablePrimary' | grep -q true; do sleep 0.3; done
}
init 27019 '{_id:"cfg",configsvr:true,members:[{_id:0,host:"localhost:27019"}]}'
init 27018 '{_id:"s1",members:[{_id:0,host:"localhost:27018"}]}'
init 27020 '{_id:"s2",members:[{_id:0,host:"localhost:27020"}]}'

mongos --configdb cfg/localhost:27019 --port 27017 --bind_ip_all --fork --logpath /data/logs/mongos.log
until mongosh --quiet --port 27017 --eval 'db.adminCommand({ping:1}).ok' | grep -q 1; do sleep 0.3; done
mongosh --quiet --port 27017 --eval 'sh.addShard("s1/localhost:27018"); sh.addShard("s2/localhost:27020")' >/dev/null
echo "FOODSTACK SHARDED MONGODB READY"
tail -f /data/logs/mongos.log
