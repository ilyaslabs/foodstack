#!/usr/bin/env bash
# Installs FoodStack on this server (TDD 17.3, deployment guide 3.2).
#
#   ./install.sh <folder> --domain example.com [--admin-domain admin.example.com] [--api-domain api.example.com]
#       [--admin-email ops@example.com] [--http] [--http-port 80] [--https-port 443] [--systemd]
#   ./install.sh <folder> --local     (trial on this computer: http://foodstack.localhost:8080, no certificates)
#
# Needs only Docker. Use sudo only for a folder you can't write to (like /opt) or for --systemd.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/lib/common.sh"

usage() { sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

target=""; domain=""; admin_domain=""; api_domain=""; admin_email=""; scheme=https
http_port=80; https_port=443; systemd=false
while (( $# )); do
  case "$1" in
    --domain) domain=$2; shift 2 ;;
    --admin-domain) admin_domain=$2; shift 2 ;;
    --api-domain) api_domain=$2; shift 2 ;;
    --admin-email) admin_email=$2; shift 2 ;;
    --http) scheme=http; shift ;;
    --http-port) http_port=$2; shift 2 ;;
    --https-port) https_port=$2; shift 2 ;;
    --systemd) systemd=true; shift ;;
    --local) domain=${domain:-foodstack.localhost}; scheme=http; http_port=8080; shift ;;
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

# --- 1. Checks -------------------------------------------------------------------------------
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

# Elasticsearch (search module, off until M1) needs a larger mmap count. Only root can set it;
# the deployment guide covers it for anyone enabling search. Docker Desktop manages its own VM.
if ! $macos && [[ $EUID -eq 0 ]] && [[ $(sysctl -n vm.max_map_count 2> /dev/null || echo 0) -lt 262144 ]]; then
  sysctl -w vm.max_map_count=262144 > /dev/null 2>&1 \
    && { echo 'vm.max_map_count=262144' > /etc/sysctl.d/99-foodstack.conf 2> /dev/null || true; }
fi

# --- 2. Directories --------------------------------------------------------------------------
info "Creating $target"
mkdir -p "$target"/{app,config,backups,lib} "$target"/logs/server 2> /dev/null \
  || fail "Cannot create $target. Choose a folder you own (like ~/foodstack) or run with sudo."
target="$(cd "$target" && pwd)"

# --- 3. Secrets ------------------------------------------------------------------------------
info "Generating secrets"
secret() { openssl rand -hex "${1:-24}"; }
signing_key=$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 2> /dev/null \
  | openssl pkcs8 -topk8 -nocrypt | grep -v -- '-----' | tr -d '\n')
pii_key=$(openssl rand -base64 32)
admin_password=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)
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
S3_ACCESS_KEY=foodstack-$(secret 6)
S3_SECRET_KEY=$(secret)
S3_PUBLIC_BUCKET=foodstack-public
S3_PRIVATE_BUCKET=foodstack-private
S3_PUBLIC_ENDPOINT=$(url "$admin_domain")
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
cp -a "$here/docker-compose.yml" "$here/.env.example" "$here/VERSION" "$here/README.md" "$target/"
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
    sudo -H -u "$SUDO_USER" docker pull -q "$image" > /dev/null
  done
fi
info "Starting the datastores"
compose up -d "${datastores[@]}"
for service in "${datastores[@]}"; do wait_healthy "$service" 300; done

info "Running database migrations"
compose run --rm --no-deps server java -jar /app/foodstack-server.jar --foodstack.migrate-only=true

info "Starting FoodStack"
compose up -d
for service in server web backoffice; do wait_healthy "$service" 300; done

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
  Password     : $admin_password   (shown once; also in $target/.env as FOODSTACK_ADMIN_PASSWORD)

Back up $target/.env now (password manager): it holds the PII key, which cannot be recovered.
Next: ./status.sh, ./logs.sh, and the first-login checklist in the deployment guide (3.4).
DONE
