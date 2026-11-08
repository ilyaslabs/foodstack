#!/usr/bin/env bash
# Installs FoodStack on this server (TDD 17.3, deployment guide 3.2).
#
#   ./install.sh <folder> --domain example.com [--admin-domain admin.example.com] [--api-domain api.example.com]
#       [--admin-email ops@example.com] [--settings my-settings.env] [--http] [--http-port 80] [--https-port 443] [--systemd]
#   ./install.sh <folder> --local     (trial on this computer: http://foodstack.localhost:8080, no certificates)
#
# A real install needs your storage buckets and map provider (handbook guides 12 and 13): put the
# S3_*, GEOCODING_*, MAP_* (and GOOGLE_MAPS_API_KEY, ROUTING_*) lines in a settings file, set them
# in the environment, or answer the questions. Needs only Docker. Use sudo only for a folder you
# can't write to (like /opt) or for --systemd.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/lib/common.sh"

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

target=""; domain=""; admin_domain=""; api_domain=""; admin_email=""; scheme=https; local_trial=false
http_port=80; https_port=443; systemd=false; settings_file=""
while (( $# )); do
  case "$1" in
    --settings) settings_file=$2; shift 2 ;;
    --domain) domain=$2; shift 2 ;;
    --admin-domain) admin_domain=$2; shift 2 ;;
    --api-domain) api_domain=$2; shift 2 ;;
    --admin-email) admin_email=$2; shift 2 ;;
    --http) scheme=http; shift ;;
    --http-port) http_port=$2; shift 2 ;;
    --https-port) https_port=$2; shift 2 ;;
    --systemd) systemd=true; shift ;;
    --local) domain=${domain:-foodstack.localhost}; scheme=http; http_port=8080; local_trial=true; shift ;;
    -h|--help) usage ;;
    -*) fail "Unknown option $1" ;;
    *) [[ -z "$target" ]] || usage; target=$1; shift ;;
  esac
done
[[ -n "$target" && -n "$domain" ]] || usage
admin_domain=${admin_domain:-$domain}
api_domain=${api_domain:-$domain}
admin_email=${admin_email:-admin@$domain}
[[ "$admin_email" == *@*.* ]] || admin_email="admin@${domain}.local"

# --- 1. Storage and maps ----------------------------------------------------------------------
# Real installs: the operator's buckets and map provider, from --settings, the environment or
# questions. A --local trial keeps photos on this machine and uses OpenStreetMap's public servers.
settings_keys=(S3_ENDPOINT S3_REGION S3_ACCESS_KEY S3_SECRET_KEY S3_PUBLIC_BUCKET S3_PRIVATE_BUCKET S3_PUBLIC_BASE_URL
  S3_PUBLIC_ENDPOINT S3_PATH_STYLE GEOCODING_PROVIDER GEOCODING_URL GEOCODING_COUNTRY_CODES GOOGLE_MAPS_API_KEY
  MAP_TILE_URL MAP_ATTRIBUTION ROUTING_PROVIDER ROUTING_URL)
if [[ -n "$settings_file" ]]; then
  [[ -f "$settings_file" ]] || fail "No settings file at $settings_file"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line%$'\r'}
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    key=${line%%=*}; value=${line#*=}
    key=$(echo "$key" | tr -d '[:space:]')
    value=${value#\"}; value=${value%\"}; value=${value#\'}; value=${value%\'}
    for known in "${settings_keys[@]}"; do
      [[ "$key" == "$known" ]] && { printf -v "$key" '%s' "$value"; export "${key?}"; }
    done
  done < "$settings_file"
fi
ask() {  # ask <variable> <question> [secret]
  local name=$1 question=$2 secret=${3:-} answer
  [[ -n "${!name:-}" ]] && return 0
  [[ -t 0 ]] || return 0
  if [[ -n "$secret" ]]; then read -r -s -p "$question: " answer; echo; else read -r -p "$question: " answer; fi
  printf -v "$name" '%s' "$answer"
}
origin_of() { echo "$1" | sed -E 's#^(https?://[^/]+).*#\1#'; }
if [[ $local_trial != true ]]; then
  info "Storage buckets (handbook guide 12)"
  ask S3_ENDPOINT "S3 endpoint (for example https://s3.eu-west-1.amazonaws.com, https://<account>.r2.cloudflarestorage.com)"
  case "${S3_ENDPOINT:-}" in
    *.amazonaws.com*) S3_REGION=${S3_REGION:-$(echo "$S3_ENDPOINT" | sed -nE 's#.*s3[.-]([a-z0-9-]+)\.amazonaws\.com.*#\1#p')}
      S3_PATH_STYLE=${S3_PATH_STYLE:-false} ;;
    *.r2.cloudflarestorage.com*) S3_REGION=${S3_REGION:-auto} ;;
    *.backblazeb2.com*) S3_REGION=${S3_REGION:-$(echo "$S3_ENDPOINT" | sed -nE 's#.*s3\.([a-z0-9-]+)\.backblazeb2\.com.*#\1#p')} ;;
  esac
  ask S3_REGION "Bucket region (for example eu-west-1; auto for Cloudflare R2)"
  ask S3_PUBLIC_BUCKET "Public bucket (images anyone may see)"
  ask S3_PRIVATE_BUCKET "Private bucket (rider papers and other private files)"
  ask S3_PUBLIC_BASE_URL "Address where the public bucket's files are served (its public URL or your CDN)"
  ask S3_ACCESS_KEY "Access key id"
  ask S3_SECRET_KEY "Secret access key" secret
  info "Maps (handbook guide 13)"
  ask GEOCODING_PROVIDER "Address search provider: google, or nominatim (any Nominatim-compatible service)"
  GEOCODING_PROVIDER=$(echo "${GEOCODING_PROVIDER:-}" | tr '[:upper:]' '[:lower:]')
  if [[ "$GEOCODING_PROVIDER" == nominatim ]]; then
    ask GEOCODING_URL "Nominatim-compatible address search URL from your provider"
  fi
  if [[ "$GEOCODING_PROVIDER" == google || "${ROUTING_PROVIDER:-}" == google ]]; then
    ask GOOGLE_MAPS_API_KEY "Google Maps Platform API key (Geocoding API and Routes API on)" secret
  fi
  ask GEOCODING_COUNTRY_CODES "Your country's two-letter code (for example pk)"
  ask MAP_TILE_URL "Map tiles URL with {z}/{x}/{y} (and your key)"
  ask MAP_ATTRIBUTION "Map credit your tile provider asks for (for example © Provider © OpenStreetMap contributors)"

  missing=()
  for key in S3_ENDPOINT S3_REGION S3_ACCESS_KEY S3_SECRET_KEY S3_PUBLIC_BUCKET S3_PRIVATE_BUCKET S3_PUBLIC_BASE_URL \
    GEOCODING_PROVIDER GEOCODING_COUNTRY_CODES MAP_TILE_URL MAP_ATTRIBUTION; do
    [[ -n "${!key:-}" ]] || missing+=("$key")
  done
  [[ "${GEOCODING_PROVIDER:-}" != nominatim || -n "${GEOCODING_URL:-}" ]] || missing+=(GEOCODING_URL)
  if [[ "${GEOCODING_PROVIDER:-}" == google || "${ROUTING_PROVIDER:-}" == google ]]; then
    [[ -n "${GOOGLE_MAPS_API_KEY:-}" ]] || missing+=(GOOGLE_MAPS_API_KEY)
  fi
  (( ${#missing[@]} == 0 )) || fail "Missing settings: ${missing[*]}. A real install needs your storage buckets and map provider: see handbook guides 12 and 13, then pass --settings <file>."
  for key in "${settings_keys[@]}"; do
    [[ "${!key:-}" != *"'"* ]] || fail "$key may not contain a single quote."
  done
  [[ "$GEOCODING_PROVIDER" == google || "$GEOCODING_PROVIDER" == nominatim ]] \
    || fail "GEOCODING_PROVIDER must be google or nominatim."
  [[ "${ROUTING_PROVIDER:-none}" =~ ^(none|osrm|google)$ ]] || fail "ROUTING_PROVIDER must be none, osrm or google."
  [[ "${ROUTING_PROVIDER:-none}" != osrm || -n "${ROUTING_URL:-}" ]] || fail "ROUTING_PROVIDER=osrm needs ROUTING_URL."
  for url in "$S3_ENDPOINT" "$S3_PUBLIC_BASE_URL" "$MAP_TILE_URL" ${GEOCODING_URL:+"$GEOCODING_URL"}; do
    [[ "$url" == https://* ]] || fail "$url must start with https://"
  done
  if [[ "${GEOCODING_URL:-}" == *nominatim.openstreetmap.org* || "$MAP_TILE_URL" == *tile.openstreetmap.org* ]]; then
    fail "OpenStreetMap's public servers allow only light use and are for --local trials. Use a hosted provider or your own (handbook guide 13)."
  fi
  [[ "$MAP_TILE_URL" == *"{z}"*"{x}"*"{y}"* ]] || fail "MAP_TILE_URL must contain {z}, {x} and {y}."
  S3_PATH_STYLE=${S3_PATH_STYLE:-true}
  S3_PUBLIC_ENDPOINT=${S3_PUBLIC_ENDPOINT:-$S3_ENDPOINT}
  MAP_TILE_ORIGIN=$(origin_of "$MAP_TILE_URL")
  # The back office page may load images from, and send uploads to, these addresses.
  upload_origin=$(origin_of "$S3_PUBLIC_ENDPOINT")
  media_origins="$(origin_of "$S3_PUBLIC_BASE_URL") $upload_origin"
  if [[ "$S3_PATH_STYLE" == false ]]; then
    host=${upload_origin#https://}
    media_origins="$media_origins https://$S3_PUBLIC_BUCKET.$host https://$S3_PRIVATE_BUCKET.$host"
  fi
  MEDIA_ORIGINS=$(echo "$media_origins" | tr ' ' '\n' | awk 'NF && !seen[$0]++' | paste -sd' ' -)
fi

# --- 1b. Checks ------------------------------------------------------------------------------
info "Checking the server"
command -v docker > /dev/null || fail "Docker is not installed (Docker Engine 27+ with Compose v2 is required)."
docker compose version > /dev/null 2>&1 || fail "Docker Compose v2 is not available (docker compose)."
docker info > /dev/null 2>&1 || fail "Cannot reach Docker. Start Docker (Docker Desktop on macOS); on Linux add your user to the docker group or use sudo."
command -v openssl > /dev/null || fail "openssl is required."
if $systemd && [[ $EUID -ne 0 ]]; then fail "--systemd installs a system service; run with sudo."; fi
macos=false; [[ $(uname -s) == Darwin ]] && macos=true
if $macos; then
  cpus=$(sysctl -n hw.ncpu 2> /dev/null || echo 0)
  mem_gb=$(( $(sysctl -n hw.memsize 2> /dev/null || echo 0) / 1024 / 1024 / 1024 ))
else
  cpus=$(nproc 2> /dev/null || echo 0)
  mem_gb=$(awk '/MemTotal/ {printf "%d", $2 / 1024 / 1024}' /proc/meminfo 2> /dev/null || echo 0)
fi
disk_gb=$(df -Pk "$(dirname "$target")" 2> /dev/null | awk 'NR==2 {printf "%d", $4 / 1024 / 1024}')
(( cpus >= 4 )) || warn "$cpus CPUs found; 4 or more are recommended."
(( mem_gb >= 15 )) || warn "${mem_gb} GB memory found; 16 GB or more are recommended."
(( disk_gb >= 100 )) || warn "${disk_gb} GB free disk found; 100 GB or more are recommended."
port_in_use() {
  if command -v lsof > /dev/null; then lsof -nP -iTCP:"$1" -sTCP:LISTEN > /dev/null 2>&1 && return 0; fi
  (ss -ltn 2> /dev/null || true) | awk '{print $4}' | grep -qE "[:.]$1\$"
}
for port in "$http_port" $([[ $scheme == https ]] && echo "$https_port"); do
  port_in_use "$port" && fail "Port $port is already in use. Pick another with --http-port / --https-port."
done
if [[ -f "$target/.installed" ]]; then
  fail "$target already holds an installation. Use upgrade.sh to update it, or uninstall.sh to remove it."
fi
target_abs=$(cd "$target" 2> /dev/null && pwd || echo "$target")
project=(--filter label=com.docker.compose.project=foodstack)
other=$(docker ps -a "${project[@]}" --format '{{.Label "com.docker.compose.project.working_dir"}}' \
  | sort -u | grep -vxF "$target_abs" || true)
[[ -z "$other" ]] || fail "FoodStack is already installed on this machine in $other. Run $other/uninstall.sh first."
# Anything left now is from an attempt that stopped part way, or a folder deleted by hand.
# Its secrets are gone, so its data can't be used: remove it and start clean.
if [[ -f "$target/.env" || -n $(docker ps -aq "${project[@]}") || -n $(docker volume ls -q "${project[@]}") ]]; then
  info "Removing an unfinished earlier install"
  for id in $(docker ps -aq "${project[@]}"); do docker rm -f "$id" > /dev/null; done
  for volume in $(docker volume ls -q "${project[@]}"); do docker volume rm "$volume" > /dev/null; done
  rm -rf "${target:?}"/{.env,app,config,lib,docker-compose.yml}
fi

# Elasticsearch runs with node.store.allow_mmap=false, so it needs no vm.max_map_count change and
# no root step. When run as root anyway, raise the limit so Elasticsearch may use mmap later.
if ! $macos && [[ $EUID -eq 0 ]] && [[ $(sysctl -n vm.max_map_count 2> /dev/null || echo 0) -lt 262144 ]]; then
  sysctl -w vm.max_map_count=262144 > /dev/null 2>&1 \
    && { echo 'vm.max_map_count=262144' > /etc/sysctl.d/99-foodstack.conf 2> /dev/null || true; }
fi

# --- 2. Directories --------------------------------------------------------------------------
info "Creating $target"
mkdir -p "$target"/{app,config/push,backups,lib} "$target"/logs/server 2> /dev/null \
  || fail "Cannot create $target. Choose a folder you own (like ~/foodstack) or run with sudo."
target="$(cd "$target" && pwd)"

# --- 3. Secrets ------------------------------------------------------------------------------
info "Generating secrets"
secret() { openssl rand -hex "${1:-24}"; }
signing_key=$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 2> /dev/null \
  | openssl pkcs8 -topk8 -nocrypt | grep -v -- '-----' | tr -d '\n')
pii_key=$(openssl rand -base64 32)
# A trial on this computer gets a fixed, documented sign-in (README); real installs get a random one.
if [[ $local_trial == true ]]; then
  admin_password=foodstack-local
else
  admin_password=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)
fi
kafka_cluster_id=$(openssl rand -base64 16 | tr '+/' '-_' | tr -d '=')

sites=$(printf '%s\n' "$domain" "$admin_domain" "$api_domain" | awk '!seen[$0]++' \
  | sed "s#^#$([[ $scheme == http ]] && echo 'http://')#" | paste -sd, - | sed 's/,/, /g')
port_suffix() { [[ $scheme == http && $http_port != 80 ]] && echo ":$http_port" || true; }
url() { echo "$scheme://$1$(port_suffix)"; }
if [[ $scheme == http && $http_port != 80 ]]; then
  # Caddy listens on 80 inside the container; name the port so it serves plain HTTP there.
  sites=$(echo "$sites" | sed -E 's#(http://[^,]+)#\1:80#g')
fi

mem_mb=$(( mem_gb * 1024 ))
java_heap=$(( mem_mb >= 30000 ? 6 : (mem_mb >= 15000 ? 4 : 2) ))g

# A value in single quotes: docker compose reads it as it is (no $ expansion), and so does bash.
q() {
  [[ "$1" != *"'"* ]] || fail "Settings may not contain a single quote: $1"
  printf "'%s'" "$1"
}

storage_and_maps() {
  if [[ $local_trial == true ]]; then
    cat << TRIAL
# Trial: photos on this machine (docker-compose.trial-storage.yml) and OpenStreetMap's public map
# servers, which allow light use only. A real install uses your buckets and map provider.
FOODSTACK_TRIAL_STORAGE=true
S3_ACCESS_KEY=foodstack-$(secret 6)
S3_SECRET_KEY=$(secret)
S3_PUBLIC_BUCKET=foodstack-public
S3_PRIVATE_BUCKET=foodstack-private
S3_PUBLIC_ENDPOINT=$(url "$admin_domain")
GEOCODING_PROVIDER=nominatim
GEOCODING_URL=https://nominatim.openstreetmap.org
GEOCODING_COUNTRY_CODES=
ROUTING_PROVIDER=none
ROUTING_URL=
TRIAL
  else
    cat << REAL
# Photos and files: your buckets (handbook guide 12). Checked every time the server starts.
S3_ENDPOINT=$(q "$S3_ENDPOINT")
S3_REGION=$(q "$S3_REGION")
S3_PATH_STYLE=$(q "$S3_PATH_STYLE")
S3_PUBLIC_ENDPOINT=$(q "$S3_PUBLIC_ENDPOINT")
S3_PUBLIC_BASE_URL=$(q "$S3_PUBLIC_BASE_URL")
S3_ACCESS_KEY=$(q "$S3_ACCESS_KEY")
S3_SECRET_KEY=$(q "$S3_SECRET_KEY")
S3_PUBLIC_BUCKET=$(q "$S3_PUBLIC_BUCKET")
S3_PRIVATE_BUCKET=$(q "$S3_PRIVATE_BUCKET")
MEDIA_ORIGINS=$(q "$MEDIA_ORIGINS")
# Maps (handbook guide 13)
GEOCODING_PROVIDER=$(q "$GEOCODING_PROVIDER")
GEOCODING_URL=$(q "${GEOCODING_URL:-}")
GEOCODING_COUNTRY_CODES=$(q "$GEOCODING_COUNTRY_CODES")
GOOGLE_MAPS_API_KEY=$(q "${GOOGLE_MAPS_API_KEY:-}")
MAP_TILE_URL=$(q "$MAP_TILE_URL")
MAP_TILE_ORIGIN=$(q "$MAP_TILE_ORIGIN")
MAP_ATTRIBUTION=$(q "$MAP_ATTRIBUTION")
# Road distances: none (straight-line estimate), osrm with ROUTING_URL, or google
ROUTING_PROVIDER=$(q "${ROUTING_PROVIDER:-none}")
ROUTING_URL=$(q "${ROUTING_URL:-}")
REAL
  fi
}

umask 077
cat > "$target/.env" << ENV
# Generated by install.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ). See .env.example for every setting.
FOODSTACK_VERSION=$(cat "$here/VERSION")
FOODSTACK_ENVIRONMENT=production
FOODSTACK_DOMAIN=$domain
FOODSTACK_ADMIN_DOMAIN=$admin_domain
FOODSTACK_API_DOMAIN=$api_domain
FOODSTACK_SITES=$sites
FOODSTACK_HOSTNAMES=$(printf '%s\n' "$domain" "$admin_domain" "$api_domain" | awk '!seen[$0]++' | paste -sd, -)
# The hostname whose root opens the back office (only when it has its own hostname).
FOODSTACK_ADMIN_ROOT_HOST=$([[ $admin_domain != "$domain" ]] && echo "$admin_domain" || echo "no-admin-host.invalid")
FOODSTACK_PUBLIC_URL=$(url "$api_domain")
FOODSTACK_WEB_URL=$(url "$domain")
FOODSTACK_ADMIN_URL=$(url "$admin_domain")
# Browsers keep the sign-in cookie only over HTTPS when it is marked Secure; a plain-HTTP trial turns that off.
FOODSTACK_REFRESH_COOKIE_SECURE=$([[ $scheme == http ]] && echo false || echo true)
HTTP_PORT=$http_port
HTTPS_PORT=$https_port
FOODSTACK_ADMIN_EMAIL=$admin_email
FOODSTACK_ADMIN_PASSWORD=$admin_password
FOODSTACK_SIGNING_KEYS=$signing_key
FOODSTACK_PII_KEY=$pii_key
MONGODB_PASSWORD=$(secret)
REDIS_PASSWORD=$(secret)
KAFKA_CLUSTER_ID=$kafka_cluster_id
ELASTICSEARCH_PASSWORD=$(secret)
KAFKA_TOPIC_PARTITIONS=24
KAFKA_REPLICATION_FACTOR=1
$(storage_and_maps)
# Email: fill these in to send order updates and sign-in codes (see .env.example), then ./restart.sh server
SMTP_HOST=
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_FROM=
# Push to riders and customers: see .env.example for the Firebase settings, then ./restart.sh server
FCM_SERVICE_ACCOUNT=
FCM_WEB_CONFIG=
FCM_VAPID_KEY=
# Card payments: none (cash only) or stripe with STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET (see .env.example)
PAYMENT_GATEWAY=none
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
STRIPE_PUBLISHABLE_KEY=
JAVA_HEAP=$java_heap
KAFKA_HEAP=1g
ES_HEAP=1g
ENV
chmod 600 "$target/.env"
openssl rand -base64 756 > "$target/config/mongodb-keyfile"
chmod 400 "$target/config/mongodb-keyfile"
umask 022

# --- 4. Application and configuration -----------------------------------------------------------
info "Copying the application"
cp -a "$here/app/." "$target/app/"
cp -a "$here/config/Caddyfile" "$here/config/backoffice.conf.template" "$target/config/"
write_storage_route "$target" "$local_trial"
cp -a "$here/docker-compose.yml" "$here/docker-compose.trial-storage.yml" "$here/.env.example" "$here/VERSION" \
  "$here/README.md" "$target/"
cp -a "$here/lib/." "$target/lib/"
for script in start stop restart status logs backup restore upgrade uninstall; do
  cp -a "$here/$script.sh" "$target/"
done
chmod +x "$target"/*.sh

# --- 5. Datastores, migrations, application -----------------------------------------------------
export FOODSTACK_HOME="$target"
# Docker Desktop on macOS keeps registry sign-in in the user's keychain, which root cannot read.
if $macos && [[ -n ${SUDO_USER:-} ]]; then
  info "Pulling images as $SUDO_USER"
  for image in $(compose config --images); do
    sudo -H -u "$SUDO_USER" docker pull -q "$image" > /dev/null 2>&1 || true
  done
fi
if trial_storage; then datastores+=(storage); fi
info "Starting the datastores"
compose up -d "${datastores[@]}"
for service in "${datastores[@]}"; do wait_healthy "$service" 300; done

info "Running database migrations"
compose run --rm --no-deps server java -jar /app/foodstack-server.jar --foodstack.migrate-only=true

info "Starting FoodStack"
compose up -d
# The server checks the buckets before it starts; say plainly what to fix when they don't work.
if ! (wait_healthy server 300); then
  problem=$(compose logs --no-log-prefix server 2> /dev/null | grep -o 'Storage check failed.*' | tail -1 || true)
  [[ -z "$problem" ]] || fail "$problem Fix it (handbook guide 12) and run install.sh again."
  fail "The server did not start; see ./logs.sh server."
fi
for service in web backoffice; do wait_healthy "$service" 300; done

# --- 6. Start on boot ------------------------------------------------------------------------
if $systemd; then
  cat > /etc/systemd/system/foodstack.service << UNIT
[Unit]
Description=FoodStack
Requires=docker.service
After=docker.service network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$target
ExecStart=$target/start.sh
ExecStop=$target/stop.sh

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload && systemctl enable foodstack.service > /dev/null
  info "Installed the systemd unit foodstack.service"
fi

date -u +%Y-%m-%dT%H:%M:%SZ > "$target/.installed"

cat << DONE

FoodStack $(cat "$target/VERSION") is running.

  Customer app : $(url "$domain")/
  Back office  : $(url "$admin_domain")/admin/
  Sign in as   : $admin_email
  Password     : $admin_password   ($([[ $local_trial == true ]] && echo "fixed for --local trials" || echo "shown once"); also in $target/.env as FOODSTACK_ADMIN_PASSWORD)

Back up $target/.env now (password manager): it holds the PII key, which cannot be recovered.
Next: ./status.sh, then the Operator handbook from guide 15 (email): https://github.com/ilyaslabs/foodstack/tree/main/docs/handbook
DONE
