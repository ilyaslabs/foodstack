#!/usr/bin/env bash
# Writes the chart's Kubernetes objects into one folder per service, so you can review them and
# apply them yourself, one service at a time, in the order you choose (deployment guide 4.5a).
# Nothing is applied: this only writes files.
#
#   ./foodstack/render.sh --out rendered -n foodstack -f my-values.yaml --set topology=split
#
# Every option other than the three below goes to `helm template` as it is (-n, -f, --set, ...).
#   --out DIR       where to write (default: rendered); emptied first
#   --release NAME  the release name, which starts every object's name (default: foodstack)
#   --offline       render without the cluster. Fine for a real install, whose keys come from your
#                   values; trial values (values-dev.yaml) would get NEW keys this way
#
# Folders, in the order a first install applies them:
#   00-secrets      token signing key, personal-data key, admin password, datastore addresses
#   01-datastores   trial datastores (values-dev.yaml only)
#   02-shared       gateway, routes, rate limits, mesh and network policies
#   03-migrations   the database migrations Job: run it, wait for it, then start the servers
#   server-<name>   one per server Deployment of the topology (monolith: server-core; split:
#                   server-core, server-order, ...), with its Service, ConfigMap, autoscaler and budget
#   web, backoffice the two apps
set -euo pipefail

chart=$(cd "$(dirname "$0")" && pwd)
out=rendered
release=foodstack
offline=false
namespace=default
args=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --out) out=${2:?--out needs a directory}; shift 2 ;;
    --release) release=${2:?--release needs a name}; shift 2 ;;
    --offline) offline=true; shift ;;
    -n|--namespace) namespace=${2:?$1 needs a namespace}; args+=("$1" "$2"); shift 2 ;;
    --namespace=*) namespace=${1#*=}; args+=("$1"); shift ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) args+=("$1"); shift ;;
  esac
done
fail() { echo "render.sh: $*" >&2; exit 1; }
command -v helm > /dev/null || fail "helm is needed (https://helm.sh/docs/intro/install/)"

# With the cluster, trial values read the keys they generated before (a real install's keys come
# from its values and are the same either way).
if ! $offline; then
  command -v kubectl > /dev/null || fail "kubectl is needed, or --offline"
  kubectl get --raw /readyz > /dev/null 2>&1 \
    || fail "kubectl cannot reach a cluster; use --offline to render without it"
  args+=(--dry-run=server)
fi

[[ -e $out && ! -f $out/README.txt && -n $(ls -A "$out" 2> /dev/null) ]] \
  && fail "$out is not empty and was not written by render.sh; choose another --out"
rm -rf "$out"
mkdir -p "$out"

helm template "$release" "$chart" "${args[@]}" | awk -v out="$out" '
  function flush() {
    if (body == "" || kind == "") { body = ""; return }
    dir = part == "" ? "shared" : part
    if (dir == "secrets") dir = "00-secrets"
    else if (dir == "datastores") dir = "01-datastores"
    else if (dir == "shared") dir = "02-shared"
    else if (dir == "migrations") dir = "03-migrations"
    system("mkdir -p \"" out "/" dir "\"")
    file = out "/" dir "/" kind "-" name ".yaml"
    printf "---\n%s", body >> file
    close(file)
    body = ""; kind = ""; name = ""; part = ""
  }
  /^---$/ { flush(); next }
  kind == "" && /^kind: / { kind = $2 }
  name == "" && /^  name: / { name = $2 }
  part == "" && /^    foodstack\.ilyaslabs\.io\/part: / { part = $2 }
  { body = body $0 "\n" }
  END { flush() }
'

# Trial values generate keys: if the cluster runs this release but its Secret is gone, applying
# 00-secrets would bring new ones. (A real install's keys come from its values.)
trial=false
grep -rqsE 'FOODSTACK_ENVIRONMENT(:|, value:) "development"' "$out" && trial=true
if $trial && ! $offline && [[ -d $out/00-secrets ]]; then
  for f in "$out"/00-secrets/Secret-*.yaml; do
    secret=$(basename "$f" .yaml); secret=${secret#Secret-}
    if ! kubectl -n "$namespace" get secret "$secret" > /dev/null 2>&1 \
      && [[ -n $(kubectl -n "$namespace" get deployment -l "app.kubernetes.io/instance=$release,app.kubernetes.io/part-of=foodstack" -o name 2> /dev/null) ]]; then
      rm -rf "$out"
      fail "FoodStack runs in $namespace but the Secret $secret is missing. New keys would make the stored data unreadable: restore the Secret, or name it in secrets.existingSecret"
    fi
  done
fi
if $trial && $offline && [[ -d $out/00-secrets ]]; then
  echo "render.sh: --offline generated NEW trial keys in $out/00-secrets: apply them only to a brand-new install" >&2
fi

cat > "$out/README.txt" << EOF
Written by render.sh for release $release in namespace $namespace. Apply each folder with
  kubectl -n $namespace apply -f $out/<folder>
in this order on a first install: 00-secrets, 01-datastores (trial only), 02-shared,
03-migrations (wait until the Job completes), then the server folders, web and backoffice in the
order you choose. See the deployment guide, 4.5a. 00-secrets holds keys: do not commit it.
EOF

echo "Written to $out:"
for d in "$out"/*/; do
  printf '  %-22s %s objects\n' "$(basename "$d")" "$(ls "$d" | wc -l | tr -d ' ')"
done
