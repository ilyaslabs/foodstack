#!/usr/bin/env bash
# Runs FoodStack on a Kubernetes cluster on this computer, in any topology, with the datastores
# in plain Docker containers beside it.
#
#   playground/k8s/up.sh                          # monolith
#   playground/k8s/up.sh --topology split         # core + order + dispatch + search
#   playground/k8s/up.sh --topology microservices # one Deployment per module
#   playground/k8s/up.sh --skip-build             # reuse the images built last time
#
# Run it again with another topology to switch: only the FoodStack release changes.
# PLAYGROUND_CLUSTER=kind uses kind instead of Floci's EKS. down.sh removes everything.
source "$(dirname "$0")/lib.sh"

topology=monolith
build=true
while [[ $# -gt 0 ]]; do
  case $1 in
    --topology) topology=${2:?--topology needs monolith, split or microservices}; shift 2 ;;
    --topology=*) topology=${1#*=}; shift ;;
    --skip-build) build=false; shift ;;
    -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "unknown option $1 (see --help)" ;;
  esac
done
[[ $topology =~ ^(monolith|split|microservices)$ ]] || fail "--topology must be monolith, split or microservices"

for tool in docker kubectl helm; do
  command -v "$tool" > /dev/null || fail "$tool is needed (https://kubernetes.io/docs/tasks/tools/, https://helm.sh/docs/intro/install/)"
done
docker info > /dev/null 2>&1 || fail "Docker is not running"
if $build; then command -v mvn > /dev/null || fail "Maven and Java 25 are needed to build FoodStack (or --skip-build)"; fi
[[ $PLAYGROUND_CLUSTER == kind ]] && { command -v kind > /dev/null || fail "kind is needed (https://kind.sigs.k8s.io)"; }
mkdir -p "$STATE_DIR"

info "Datastores in Docker (network $PLAYGROUND_NETWORK)"
for store in mongodb redis kafka elasticsearch mailpit floci; do
  "$PLAYGROUND_DIR/datastores/$store.sh"
done

# --- The cluster --------------------------------------------------------------------------------
NODE=""
if [[ $PLAYGROUND_CLUSTER == floci ]]; then
  NODE=floci-aws-eks-foodstack
  if ! aws_cli eks describe-cluster --name foodstack > /dev/null 2>&1; then
    info "Creating the EKS cluster on Floci (a k3s node in Docker)"
    subnet=$(aws_cli ec2 describe-subnets --query 'Subnets[0].SubnetId' --output text)
    aws_cli eks create-cluster --name foodstack --role-arn arn:aws:iam::000000000000:role/foodstack-eks \
      --resources-vpc-config "subnetIds=$subnet" > /dev/null
  fi
  cluster_active() { [[ $(aws_cli eks describe-cluster --name foodstack --query cluster.status --output text) == ACTIVE ]]; }
  wait_for "$NODE (EKS cluster)" 300 cluster_active
  wait_for "$NODE" 120 docker exec "$NODE" test -s /etc/rancher/k3s/k3s.yaml
  # k3s writes its own address; Floci publishes the API on 127.0.0.1:6500.
  docker exec "$NODE" cat /etc/rancher/k3s/k3s.yaml | sed -E 's#https://[^:]+:6443#https://127.0.0.1:6500#' > "$KUBECONFIG"
elif [[ $PLAYGROUND_CLUSTER == kind ]]; then
  NODE=foodstack-playground-control-plane
  if ! kind get clusters 2> /dev/null | grep -qx foodstack-playground; then
    info "Creating the kind cluster"
    kind create cluster --name foodstack-playground --image "$KIND_NODE_IMAGE" --kubeconfig "$KUBECONFIG"
  fi
  kind get kubeconfig --name foodstack-playground > "$KUBECONFIG"
else
  fail "PLAYGROUND_CLUSTER must be floci or kind"
fi
chmod 600 "$KUBECONFIG"
# The node joins the datastores' network, so pods reach them on their fixed addresses.
docker network connect "$PLAYGROUND_NETWORK" "$NODE" 2> /dev/null || true
wait_for "$NODE (Kubernetes API)" 120 kubectl get --raw /readyz
kubectl wait --for=condition=Ready node --all --timeout=180s > /dev/null

# --- Gateway API and Istio ambient (deployment guide 4.1) ---------------------------------------
if ! helm status ztunnel -n istio-system > /dev/null 2>&1; then
  info "Installing the Gateway API and Istio $ISTIO_VERSION (ambient)"
  kubectl apply --server-side -f \
    "https://github.com/kubernetes-sigs/gateway-api/releases/download/$GATEWAY_API_VERSION/standard-install.yaml" > /dev/null
  helm repo add istio https://istio-release.storage.googleapis.com/charts > /dev/null 2>&1 || true
  platform=()
  [[ $PLAYGROUND_CLUSTER == floci ]] && platform=(--set global.platform=k3s)
  istio() { helm upgrade --install "$1" "istio/$2" -n istio-system --create-namespace --version "$ISTIO_VERSION" --wait --timeout 10m "${@:3}"; }
  istio istio-base base > /dev/null
  istio istiod istiod --set profile=ambient --set pilot.env.PILOT_JWT_ENABLE_REMOTE_JWKS=envoy \
    --set pilot.resources.requests.cpu=50m --set pilot.resources.requests.memory=256Mi "${platform[@]}" > /dev/null
  istio istio-cni cni --set profile=ambient "${platform[@]}" > /dev/null
  istio ztunnel ztunnel "${platform[@]}" > /dev/null
fi

# --- FoodStack images ---------------------------------------------------------------------------
IMAGE=ilyasdotdev/ilyaslabs-foodstack
if $build; then
  info "Building the server and the two apps (Maven, pnpm), then the images"
  (cd "$REPO_DIR" && mvn -B -q -T 1C -DskipTests -pl deploy/unit-deployer -am package)
  docker build -q -t "$IMAGE:server-playground" "$REPO_DIR/app/foodstack-server" > /dev/null
  docker build -q -t "$IMAGE:web-playground" -f "$REPO_DIR/frontend/docker/web.Dockerfile" "$REPO_DIR/frontend" > /dev/null
  docker build -q -t "$IMAGE:backoffice-playground" -f "$REPO_DIR/frontend/docker/backoffice.Dockerfile" "$REPO_DIR/frontend" > /dev/null
fi
info "Loading the images into the cluster"
for part in server web backoffice; do
  docker image inspect "$IMAGE:$part-playground" > /dev/null 2>&1 || fail "no image $IMAGE:$part-playground; run without --skip-build"
  # (not `kind load`: it fails on Docker's containerd image store with "content digest not found")
  docker save "$IMAGE:$part-playground" | docker exec -i "$NODE" ctr -n k8s.io images import - > /dev/null
done

# --- FoodStack ----------------------------------------------------------------------------------
info "Installing FoodStack ($topology)"
upgrade=false
helm status foodstack -n foodstack > /dev/null 2>&1 && upgrade=true
helm upgrade --install foodstack "$REPO_DIR/k8s/helm/foodstack" -n foodstack --create-namespace \
  -f "$PLAYGROUND_DIR/values-playground.yaml" --set topology="$topology" --wait --timeout 20m > /dev/null
# A new image under the same tag: restart so every pod runs it.
$build && $upgrade && kubectl -n foodstack rollout restart deployment > /dev/null && \
  kubectl -n foodstack rollout status deployment --timeout 10m > /dev/null

# --- The way in ---------------------------------------------------------------------------------
if [[ -f $STATE_DIR/port-forward.pid ]]; then kill "$(cat "$STATE_DIR/port-forward.pid")" 2> /dev/null || true; fi
nohup kubectl -n foodstack port-forward --address 127.0.0.1 svc/foodstack-istio "$PLAYGROUND_PORT:80" \
  > "$STATE_DIR/port-forward.log" 2>&1 &
echo $! > "$STATE_DIR/port-forward.pid"
wait_for "the gateway on port $PLAYGROUND_PORT" 60 curl -fsS -H "Host: foodstack.localhost" \
  "http://127.0.0.1:$PLAYGROUND_PORT/api/v1/settings/locale"

password=$(kubectl -n foodstack get secret foodstack-secrets -o jsonpath='{.data.FOODSTACK_ADMIN_PASSWORD}' | base64 -d)
cat << EOF

FoodStack is running ($topology on $PLAYGROUND_CLUSTER):
  Customer app   http://foodstack.localhost:$PLAYGROUND_PORT
  Back office    http://admin.foodstack.localhost:$PLAYGROUND_PORT/admin/
                 admin@foodstack.localhost / $password
  Emails         http://localhost:8025
  Pods           KUBECONFIG=$KUBECONFIG kubectl -n foodstack get pods

Switch topology: $0 --topology split --skip-build     Stop and remove: $PLAYGROUND_DIR/down.sh
EOF
