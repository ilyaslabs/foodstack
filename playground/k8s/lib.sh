# Shared settings for the playground scripts. Every value can be changed in the environment.
set -euo pipefail

PLAYGROUND_NETWORK=${PLAYGROUND_NETWORK:-foodstack-playground}
# A private subnet for the playground's containers; change it if it clashes with one of yours.
PLAYGROUND_SUBNET=${PLAYGROUND_SUBNET:-10.213.0}
PLAYGROUND_PORT=${PLAYGROUND_PORT:-8080}

# Fixed addresses, so the cluster's pods and the datastores' own settings agree. Docker gives
# other containers (Floci and its cluster node) addresses from .128 up; Floci itself takes .5.
FLOCI_IP=$PLAYGROUND_SUBNET.5
MONGODB_IP=$PLAYGROUND_SUBNET.10
REDIS_IP=$PLAYGROUND_SUBNET.11
KAFKA_IP=$PLAYGROUND_SUBNET.12
ELASTICSEARCH_IP=$PLAYGROUND_SUBNET.13
MAILPIT_IP=$PLAYGROUND_SUBNET.14

ELASTICSEARCH_PASSWORD=playground

# Versions, pinned like the unit deployer's.
MONGODB_IMAGE=mongo:8.2
REDIS_IMAGE=redis:8.2-alpine
KAFKA_IMAGE=apache/kafka:4.1.1
ELASTICSEARCH_IMAGE=elasticsearch:9.4.0
MAILPIT_IMAGE=axllent/mailpit:v1.27
ISTIO_VERSION=1.30.8
GATEWAY_API_VERSION=v1.3.0

PLAYGROUND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$PLAYGROUND_DIR/../.." && pwd)"
STATE_DIR="$PLAYGROUND_DIR/.state"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

network() {
  docker network inspect "$PLAYGROUND_NETWORK" > /dev/null 2>&1 \
    || docker network create --subnet "$PLAYGROUND_SUBNET.0/24" --ip-range "$PLAYGROUND_SUBNET.128/25" "$PLAYGROUND_NETWORK" > /dev/null
}

# run_container <name> <ip> <docker run arguments...>: starts it once; a stopped one is started again.
run_container() {
  local name=$1 ip=$2; shift 2
  if docker inspect "$name" > /dev/null 2>&1; then
    docker start "$name" > /dev/null
  else
    docker run -d --name "$name" --network "$PLAYGROUND_NETWORK" --ip "$ip" --restart unless-stopped "$@" > /dev/null
  fi
}

# wait_for <what> <seconds> <command...>
wait_for() {
  local what=$1 seconds=$2; shift 2
  for (( i = 0; i < seconds; i += 2 )); do
    if "$@" > /dev/null 2>&1; then return 0; fi
    sleep 2
  done
  fail "$what did not start within ${seconds}s. See: docker logs ${what%% *}"
}
