#!/usr/bin/env bash
# Runs FoodStack, in any topology, on the Kubernetes cluster your kubectl points at (an EKS cluster
# on Floci, kind, k3s, ...), with the datastores in plain Docker containers beside it. See README.md.
#
#   playground/k8s/up.sh --version 1.0.0                        # a release from Docker Hub (monolith)
#   playground/k8s/up.sh --version 1.0.0 --topology split       # core + order + dispatch + search
#   playground/k8s/up.sh --registry <registry>/<repository>     # your own build, pushed there
#   playground/k8s/up.sh --registry ... --topology microservices --skip-build
#
# --registry is where the cluster pulls your build from (Floci: the ECR repositoryUri). --push-to is
# where Docker pushes, when that differs (Floci on Linux: localhost:4566/000000000000/us-east-1/<repo>).
# Run it again with another topology to switch: only the FoodStack release changes.
source "$(dirname "$0")/lib.sh"

topology=monolith
build=true
registry=""
push_to=""
version=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --topology) topology=${2:?--topology needs monolith, split or microservices}; shift 2 ;;
    --registry) registry=${2:?--registry needs <registry>/<repository>}; shift 2 ;;
    --push-to) push_to=${2:?--push-to needs <registry>/<repository>}; shift 2 ;;
    --version) version=${2:?--version needs a released version such as 1.0.0}; build=false; shift 2 ;;
    --skip-build) build=false; shift ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "unknown option $1 (see --help)" ;;
  esac
done
[[ $topology =~ ^(monolith|split|microservices)$ ]] || fail "--topology must be monolith, split or microservices"
[[ -n $registry || -n $version ]] || fail "give --version for a release from Docker Hub, or --registry <registry>/<repository> for your own build (see --help)"

for tool in docker kubectl helm openssl; do
  command -v "$tool" > /dev/null || fail "$tool is needed (https://kubernetes.io/docs/tasks/tools/, https://helm.sh/docs/intro/install/)"
done
docker info > /dev/null 2>&1 || fail "Docker is not running"
$build && { command -v mvn > /dev/null || fail "Maven and Java 25 are needed to build FoodStack (or --skip-build)"; }
kubectl get --raw /readyz > /dev/null 2>&1 \
  || fail "kubectl cannot reach a cluster (context: $(kubectl config current-context 2> /dev/null || echo none)); see README.md"
mkdir -p "$STATE_DIR"
info "Cluster: $(kubectl config current-context)"

info "Datastores in Docker (network $PLAYGROUND_NETWORK)"
for store in mongodb redis kafka elasticsearch mailpit; do
  "$PLAYGROUND_DIR/datastores/$store.sh"
done

# --- Gateway API and Istio ambient (deployment guide 4.1), once per cluster ----------------------
if ! helm status ztunnel -n istio-system > /dev/null 2>&1; then
  info "Installing the Gateway API and Istio $ISTIO_VERSION (ambient)"
  kubectl apply --server-side -f \
    "https://github.com/kubernetes-sigs/gateway-api/releases/download/$GATEWAY_API_VERSION/standard-install.yaml" > /dev/null
  helm repo add istio https://istio-release.storage.googleapis.com/charts > /dev/null 2>&1 || true
  platform=()
  kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.kubeletVersion}' | grep -q k3s && platform=(--set global.platform=k3s)
  istio() { helm upgrade --install "$1" "istio/$2" -n istio-system --create-namespace --version "$ISTIO_VERSION" --wait --timeout 10m "${@:3}"; }
  istio istio-base base > /dev/null
  istio istiod istiod --set profile=ambient --set pilot.env.PILOT_JWT_ENABLE_REMOTE_JWKS=envoy \
    --set pilot.resources.requests.cpu=50m --set pilot.resources.requests.memory=256Mi "${platform[@]}" > /dev/null
  istio istio-cni cni --set profile=ambient "${platform[@]}" > /dev/null
  istio ztunnel ztunnel "${platform[@]}" > /dev/null
fi

# --- FoodStack images ---------------------------------------------------------------------------
images=()
if [[ -n $version ]]; then
  images=(--set image.tag="$version")
else
  tag=playground
  if $build; then
    info "Building the server and the two apps (Maven, pnpm), then the images"
    (cd "$REPO_DIR" && mvn -B -q -T 1C -DskipTests -pl deploy/unit-deployer -am package)
    push_to=${push_to:-$registry}
    docker build -q -t "$push_to:server-$tag" "$REPO_DIR/app/foodstack-server" > /dev/null
    docker build -q -t "$push_to:web-$tag" -f "$REPO_DIR/frontend/docker/web.Dockerfile" "$REPO_DIR/frontend" > /dev/null
    docker build -q -t "$push_to:backoffice-$tag" -f "$REPO_DIR/frontend/docker/backoffice.Dockerfile" "$REPO_DIR/frontend" > /dev/null
    info "Pushing the images to $push_to"
    for part in server web backoffice; do docker push -q "$push_to:$part-$tag" > /dev/null; done
  fi
  # The chart's image is <registry>/<repository>:<part>-<tag>, always pulled again (same tag, new build).
  images=(--set image.registry="${registry%%/*}" --set image.repository="${registry#*/}"
    --set image.tag="$tag" --set image.pullPolicy=Always)
fi

# --- FoodStack ----------------------------------------------------------------------------------
# The server's keys, made once like an operator does (Kubernetes guide, section 6), kept until
# down.sh removes the namespace.
kubectl create namespace foodstack --dry-run=client -o yaml | kubectl apply -f - > /dev/null
if ! kubectl -n foodstack get secret foodstack-keys > /dev/null 2>&1; then
  info "Making the server's keys (Secret foodstack-keys)"
  kubectl -n foodstack create secret generic foodstack-keys \
    --from-literal=FOODSTACK_SIGNING_KEYS="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 2> /dev/null \
      | openssl pkcs8 -topk8 -nocrypt | grep -v -- ----- | tr -d '\n')" \
    --from-literal=FOODSTACK_PII_KEY="$(openssl rand -base64 32)" \
    --from-literal=FOODSTACK_INTERNAL_TOKEN="$(openssl rand -hex 24)" \
    --from-literal=FOODSTACK_SERVICE_CLIENT_SECRET="$(openssl rand -hex 24)" \
    --from-literal=FOODSTACK_ADMIN_PASSWORD="$(openssl rand -hex 12)" > /dev/null
fi

info "Installing FoodStack ($topology)"
upgrade=false
helm status foodstack -n foodstack > /dev/null 2>&1 && upgrade=true
helm upgrade --install foodstack "$REPO_DIR/k8s/helm/foodstack" -n foodstack --create-namespace \
  -f "$PLAYGROUND_DIR/values-playground.yaml" --set topology="$topology" "${images[@]}" --wait --timeout 20m > /dev/null
# A new build under the same tag: restart so every pod runs it.
$build && $upgrade && kubectl -n foodstack rollout restart deployment > /dev/null && \
  kubectl -n foodstack rollout status deployment --timeout 10m > /dev/null

# --- The way in ---------------------------------------------------------------------------------
if [[ -f $STATE_DIR/port-forward.pid ]]; then kill "$(cat "$STATE_DIR/port-forward.pid")" 2> /dev/null || true; fi
nohup kubectl -n foodstack port-forward --address 127.0.0.1 svc/foodstack-istio "$PLAYGROUND_PORT:80" \
  > "$STATE_DIR/port-forward.log" 2>&1 &
echo $! > "$STATE_DIR/port-forward.pid"
wait_for "the gateway on port $PLAYGROUND_PORT" 60 curl -fsS -H "Host: foodstack.localhost" \
  "http://127.0.0.1:$PLAYGROUND_PORT/api/v1/settings/locale"

password=$(kubectl -n foodstack get secret foodstack-keys -o jsonpath='{.data.FOODSTACK_ADMIN_PASSWORD}' | base64 -d)
cat << EOF

FoodStack is running ($topology):
  Customer app   http://foodstack.localhost:$PLAYGROUND_PORT
  Back office    http://admin.foodstack.localhost:$PLAYGROUND_PORT/admin/
                 admin@foodstack.localhost / $password
  Emails         http://localhost:8025
  Pods           kubectl -n foodstack get pods

Switch topology: $0 ... --topology split --skip-build     Stop: $PLAYGROUND_DIR/down.sh
EOF
