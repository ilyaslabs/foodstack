#!/usr/bin/env bash
# Removes what up.sh made: the port forward, the FoodStack release and the datastore containers
# (with their data). Your cluster, Floci and its buckets stay; remove those as you made them
# (README.md).
source "$(dirname "$0")/lib.sh"

if [[ -f $STATE_DIR/port-forward.pid ]]; then
  kill "$(cat "$STATE_DIR/port-forward.pid")" 2> /dev/null || true
fi
if kubectl get --raw /readyz > /dev/null 2>&1 && helm status foodstack -n foodstack > /dev/null 2>&1; then
  info "Removing FoodStack from $(kubectl config current-context)"
  helm uninstall foodstack -n foodstack --wait > /dev/null
  kubectl delete namespace foodstack --wait=false > /dev/null 2>&1 || true
fi
info "Removing the datastore containers"
docker rm -f -v fsplay-mongodb fsplay-redis fsplay-kafka fsplay-elasticsearch fsplay-mailpit > /dev/null 2>&1 || true
rm -rf "$STATE_DIR"
info "Done. The network $PLAYGROUND_NETWORK stays while Floci uses it: docker network rm $PLAYGROUND_NETWORK"
