#!/usr/bin/env bash
# Removes the playground: the port forward, the cluster, the datastore containers (with their
# data) and the network. The built images stay; up.sh --skip-build reuses them.
source "$(dirname "$0")/lib.sh"

if [[ -f $STATE_DIR/port-forward.pid ]]; then
  kill "$(cat "$STATE_DIR/port-forward.pid")" 2> /dev/null || true
fi
if command -v kind > /dev/null && kind get clusters 2> /dev/null | grep -qx foodstack-playground; then
  kind delete cluster --name foodstack-playground
fi
if docker inspect fsplay-floci > /dev/null 2>&1; then
  info "Deleting the EKS cluster"
  aws_cli eks delete-cluster --name foodstack > /dev/null 2>&1 || true
fi
# Floci's own containers too, in case it was stopped before it could remove them.
info "Removing the containers and the network"
docker rm -f -v fsplay-mongodb fsplay-redis fsplay-kafka fsplay-elasticsearch fsplay-mailpit fsplay-floci \
  floci-aws-eks-foodstack floci-aws-ecr-registry > /dev/null 2>&1 || true
docker network rm "$PLAYGROUND_NETWORK" > /dev/null 2>&1 || true
rm -rf "$STATE_DIR"
info "Playground removed"
