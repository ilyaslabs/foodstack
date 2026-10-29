# FoodStack: Deployment Guide

| | |
|---|---|
| Status | Draft v1, written with the design ([TDD](TDD.md) sections 16 to 19). Commands and file names are the planned interface; they become runnable from milestone M0 (single server) and M5 (Kubernetes) |
| Audience | The operator's DevOps or whoever installs and runs FoodStack |

## 1. Choose your path

| | Single server (unit deployer) | Kubernetes (Helm) |
|---|---|---|
| Best for | Launch, pilots, one city, up to about 200 orders per hour | Growth, high availability, several cities, independent scaling |
| You need | One Linux server with Docker | A Kubernetes cluster, plus managed or operator-run datastores |
| Datastores | Included and installed for you (MongoDB, Kafka, Redis, Elasticsearch, MinIO) | Bring your own (recommended) or run them in-cluster for trials |
| Topologies | Monolith | `monolith`, `split`, `microservices` or `custom`, switchable at any time |
| High availability | No (one machine) | Yes (replicas across nodes and zones) |
| Effort to run | Low | Medium |

Start on a single server. Moving to Kubernetes later is a data copy plus configuration (section 5); no code changes.

## 2. Before you start (both paths)

### 2.1 Accounts and services

| Item | Needed for | Notes |
|---|---|---|
| Domain names | Everything | Three hostnames, for example `example.com` (customer app), `admin.example.com` (back office), `api.example.com` (API and login) |
| SMTP account | Email notifications, login codes | Any provider; TLS on port 587 recommended |
| Firebase Cloud Messaging project | Push notifications | Service-account JSON key |
| Payment gateway account | Card payments | Only if cards are enabled; hosted payment page recommended at launch |
| Map services | Address search, routing, ETAs | Public OpenStreetMap services are for demos only; for production self-host Nominatim and OSRM for your region, or use the Google Maps adapter with an API key |
| S3-compatible storage (Kubernetes only) | Images | AWS S3 or any compatible provider; the single server includes MinIO |

### 2.2 Secrets you will hold

Generated for you on a single server; created by you on Kubernetes.

| Secret | Purpose |
|---|---|
| JWT signing key pair (ES256) | Signs login tokens. Rotating it keeps the previous key valid until old tokens expire |
| PII encryption key (32 random bytes) | Encrypts emails and phone numbers at rest. **Back it up separately; losing it makes that data unreadable** |
| Module-Switch internal token | Protects module-to-module calls |
| Datastore passwords | MongoDB, Redis, Elasticsearch, MinIO or S3, Kafka (if secured) |
| SMTP, FCM, payment gateway and maps credentials | External providers |

## 3. Single server (unit deployer)

### 3.1 Server requirements

| | Minimum (trial) | Recommended (pilot city) |
|---|---|---|
| CPU | 4 vCPU | 8 vCPU |
| Memory | 16 GB | 32 GB |
| Disk | 100 GB SSD | 250 GB SSD (NVMe preferred), plus off-server backup storage |
| OS | 64-bit Linux (Ubuntu 24.04 LTS, Debian 12, RHEL 9 family), amd64 or arm64 | Same |
| Software | Docker Engine 27+ with Compose v2 | Same |
| Network | Ports 80 and 443 open to the internet; DNS A/AAAA records for your three hostnames pointing at the server | Same, plus SSH restricted to your IPs |

### 3.2 Install

```bash
# 1. Download and unpack the release
curl -LO https://github.com/ilyaslabs/foodstack/releases/download/v1.0.0/unit-deployer-1.0.0.zip
unzip unit-deployer-1.0.0.zip && cd unit-deployer-1.0.0

# 2. Install into a directory of your choice (data, logs and backups live there)
sudo ./install.sh /opt/foodstack \
  --domain example.com \
  --admin-domain admin.example.com \
  --api-domain api.example.com \
  --admin-email ops@example.com
```

`install.sh`:

1. Checks Docker, Compose, CPU, memory, disk and that ports 80 and 443 are free.
2. Sets `vm.max_map_count=262144` (needed by Elasticsearch) and makes it persistent.
3. Creates `/opt/foodstack/{data,logs,config,backups}` with one data folder per service.
4. Generates strong passwords, the JWT key pair, the PII key and the internal token into `/opt/foodstack/.env` (permissions 600).
5. Copies the application, web apps and configuration.
6. Starts the datastores, waits for them to be healthy, runs database migrations once, then starts the application and the web apps.
7. Optionally installs a systemd unit so FoodStack starts on boot (`--systemd`).
8. Prints a one-time link to set the first admin password.

### 3.3 Configure

Edit `/opt/foodstack/.env`, then run `./restart.sh server`. The main settings:

| Variable | Example | Notes |
|---|---|---|
| `FOODSTACK_DOMAIN`, `FOODSTACK_ADMIN_DOMAIN`, `FOODSTACK_API_DOMAIN` | `example.com` | Set by `install.sh` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` | | Required for login codes and emails |
| `FCM_CREDENTIALS_FILE` | `/opt/foodstack/config/fcm.json` | Push notifications |
| `MAPS_PROVIDER` | `osm` or `google` | Plus `NOMINATIM_URL`, `OSRM_URL` or `GOOGLE_MAPS_API_KEY` |
| `PAYMENTS_CARD_ENABLED` | `false` | Turn on once the gateway is configured |
| `JAVA_HEAP`, `ES_HEAP` | `4g`, `2g` | Sized by `install.sh` from server memory |

Everything else (branding, languages, currency, timezone, fees, commission, feature switches) is set in the admin panel after login.

### 3.4 First login checklist

1. Open `https://admin.example.com`, set the admin password, and enable two-factor login.
2. **Settings → Branding:** name, logos, colours, font.
3. **Settings → Locale:** languages, default language, currency, timezone.
4. **Settings → Business rules:** service fee, cancellation policy, auto-reject timeout, tip options.
5. **Settings → Features:** pickup, operator rider service, card payments, saved cards, store direct payment collection.
6. **Notifications:** send a test email and a test push.
7. **Delivery:** if you run your own riders, switch on the operator rider service and set operator delivery pricing and rider pay; otherwise stores deliver themselves.
8. **Stores:** onboard the first store and choose whether it may use operator delivery; let the owner set delivery mode, hours, zones and menu.
9. Place a test order end to end, then delete it from the admin panel.

### 3.5 Day-to-day operations

| Task | Command (from the install directory) |
|---|---|
| Start everything | `./start.sh` |
| Stop everything (data kept) | `./stop.sh` |
| Restart everything or one service | `./restart.sh` or `./restart.sh server` |
| Health of every service | `./status.sh` |
| Follow logs | `./logs.sh server` (or `mongodb`, `kafka`, `proxy`, …) |
| Back up now | `./backup.sh` |
| Upgrade | `./upgrade.sh /path/to/unit-deployer-1.1.0.zip` |

### 3.6 Backups and restore

- `backup.sh` writes `backups/<timestamp>/` with a MongoDB dump (all `foodstack_*` databases), a MinIO mirror of the media buckets, and the configuration including `.env`.
- Schedule it daily with cron or the systemd timer installed by `--systemd`, and **copy backups off the server** (for example to S3 or another machine). Keep the PII key and `.env` in a password manager as well.
- Kafka, Redis and Elasticsearch need no backup: events are delivered from MongoDB's outbox, Redis is a cache, and search is rebuilt from the data.
- Restore: `./restore.sh backups/<timestamp>` stops the application, restores MongoDB and media, rebuilds search, and starts again.
- Test a restore on a spare server at least once before going live.

### 3.7 Upgrades

1. Read the release notes for the new version.
2. `./upgrade.sh unit-deployer-<new>.zip` takes a backup, replaces the application, web apps and compose file, runs migrations, and restarts with a short downtime (usually under a minute).
3. If something goes wrong: `./upgrade.sh --rollback` restores the previous files and the pre-upgrade backup.

Migrations are always backward compatible with the previous release, so a rollback of the application does not require a data restore unless the release notes say so.

### 3.8 Hardening checklist

- [ ] Only ports 22, 80 and 443 open; SSH by key only, from known IPs.
- [ ] OS security updates automatic (`unattended-upgrades` or equivalent).
- [ ] Two-factor login for every admin and finance user.
- [ ] Daily off-server backups, and one test restore done.
- [ ] Real SMTP, maps and payment credentials set; demo map services not used.
- [ ] `FOODSTACK_ENVIRONMENT=production` (blocks the `test-seed` demo data).
- [ ] Disk usage alert at 80% (`status.sh` reports it).

## 4. Kubernetes (Helm)

### 4.1 Cluster prerequisites

| Component | Version | Notes |
|---|---|---|
| Kubernetes | 1.31+ | Any CNCF-conformant distribution or managed service |
| Gateway API CRDs | v1.2+ | Standard channel |
| Istio | Ambient mode | Recommended: mTLS between all pods and per-request load balancing. The chart also works without it (`mesh.enabled=false`) |
| cert-manager | 1.16+ | Issues TLS certificates (for example with Let's Encrypt) |
| Metrics Server | | Needed for autoscaling |
| External Secrets Operator | Optional | If secrets live in a cloud secret manager or Vault |
| Nodes | 3+ across 2+ zones | Reference: 3 × 4 vCPU / 16 GB for `monolith` with managed datastores |

### 4.2 Datastores

Use managed services or operator-run clusters in production. Minimums for a reference production setup:

| Datastore | Production minimum | Notes |
|---|---|---|
| MongoDB | 3-member replica set, 7.0+ | Transactions require a replica set. FoodStack creates one database per module (`foodstack_<module>`) |
| Kafka | 3 brokers, 3.7+ (KRaft) | Topics are created by FoodStack with 24 partitions and replication factor 3 |
| Redis | Primary with a replica, or Redis Cluster, 7+ | Cache and coordination only |
| Elasticsearch | 3 nodes, 9.x | Search indices rebuildable from data |
| Object storage | S3 or compatible | Two buckets: `foodstack-public`, `foodstack-private` |

For trials, `-f values-dev.yaml` runs all of them inside the cluster with small sizes. Do not use it for production.

### 4.3 Create secrets

```bash
kubectl create namespace foodstack
kubectl label namespace foodstack istio.io/dataplane-mode=ambient

kubectl -n foodstack create secret generic foodstack-datastores \
  --from-literal=MONGODB_URI='mongodb+srv://…' \
  --from-literal=KAFKA_BOOTSTRAP='kafka-1:9092,kafka-2:9092,kafka-3:9092' \
  --from-literal=REDIS_URL='rediss://…' \
  --from-literal=ELASTICSEARCH_URL='https://…' \
  --from-literal=S3_ACCESS_KEY='…' --from-literal=S3_SECRET_KEY='…'

kubectl -n foodstack create secret generic foodstack-keys \
  --from-file=jwt-private.pem --from-file=jwt-public.pem \
  --from-literal=PII_KEY="$(openssl rand -base64 32)"

kubectl -n foodstack create secret generic foodstack-providers \
  --from-literal=SMTP_PASSWORD='…' --from-file=fcm.json \
  --from-literal=PAYMENT_GATEWAY_SECRET='…'
```

The internal Module-Switch token is generated by the chart. With External Secrets Operator, set `secrets.externalSecrets.enabled=true` and map the same keys instead.

### 4.4 Minimal values

```yaml
# my-values.yaml
global:
  domains:
    web: example.com
    backoffice: admin.example.com
    api: api.example.com
  tls:
    clusterIssuer: letsencrypt-prod

topology: monolith            # monolith | split | microservices | custom

secrets:
  datastores: foodstack-datastores
  keys: foodstack-keys
  providers: foodstack-providers

storage:
  endpoint: ""                # empty = AWS S3
  region: eu-central-1

mesh:
  enabled: true               # Istio ambient

server:
  resources:
    requests: { cpu: "1", memory: 2Gi }
    limits:   { memory: 3Gi }
```

### 4.5 Install and verify

```bash
helm install foodstack oci://ghcr.io/ilyaslabs/charts/foodstack \
  --version 1.0.0 -n foodstack -f my-values.yaml

kubectl -n foodstack get pods,httproute,hpa
kubectl -n foodstack logs deploy/foodstack-core | grep "Module-Switch modules"
```

The install runs migrations in a pre-install job first. Then follow the first-login checklist in section 3.4.

### 4.6 Scaling and topologies

| Goal | Change |
|---|---|
| More capacity, same shape | Raise `server.deployments.<name>.autoscaling.max` (or `replicas`) |
| Scale the busiest modules independently | `helm upgrade … --set topology=split` |
| One Deployment per module | `--set topology=microservices` |
| Your own grouping | `topology: custom` with `server.deployments` listing modules per Deployment ([TDD 18.2](TDD.md#182-module-topology-one-setting-any-shape)) |

A topology change rolls one Deployment at a time; calls between modules are retried while targets start, so traffic keeps flowing. **No data moves** when you change topology.

Kafka consumers: the chart keeps `replicas × listener concurrency ≤ 24` per module and warns if you exceed it, since extra consumers would sit idle.

### 4.7 Moving one module's data to its own MongoDB cluster

Only when the shared cluster is the bottleneck or you need isolation.

1. Create the new cluster (replica set).
2. Copy the module's database with near-zero downtime using MongoDB's live sync tool, or with a short maintenance window: `mongodump --db foodstack_order` then `mongorestore` to the new cluster.
3. Create a secret with the new URI and set it for that module:
   ```yaml
   server:
     moduleDefaults:
       order: { mongodbUriSecret: order-mongodb }
   ```
4. `helm upgrade`. Only the Deployment running `order` restarts.
5. After verifying, drop `foodstack_order` from the old cluster.

### 4.8 Growing MongoDB: replicas and shards

- **More replicas:** add members to the replica set; no FoodStack change.
- **Sharding:** convert the replica set into the first shard of a sharded cluster, point `MONGODB_URI` at `mongos`, then shard each collection with the key listed in [TDD 6.3](TDD.md#63-designed-for-sharding). FoodStack's CI already tests every query against a sharded cluster.

### 4.9 Observability

```bash
helm install foodstack-observability oci://ghcr.io/ilyaslabs/charts/foodstack-observability \
  -n monitoring --create-namespace
```

Installs the OpenTelemetry Collector, Prometheus, Grafana (FoodStack dashboards), Tempo and Loki with alert rules for error rate, latency, outbox lag, Kafka consumer lag, dead-letter growth and certificate expiry. To use an existing stack instead, point `observability.otlpEndpoint` at your collector.

### 4.10 Upgrades and rollback

```bash
helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.1.0 -n foodstack -f my-values.yaml
helm rollback foodstack -n foodstack      # if needed
```

Migrations run in a pre-upgrade job and are backward compatible with the previous release, so pods roll without downtime and a rollback is safe.

### 4.11 Backup and disaster recovery

- MongoDB: use the provider's continuous backup (point-in-time restore) or scheduled `mongodump` per `foodstack_*` database.
- Object storage: bucket versioning plus cross-region replication.
- Secrets: kept in your secret manager; the PII key and JWT keys must be recoverable.
- Kafka, Redis and Elasticsearch: no backup required (rebuildable).
- Recovery target for the reference setup: restore point under 15 minutes old, service back within 1 hour. Run a recovery drill every quarter.

### 4.12 Security checklist

- [ ] Namespace in Istio ambient mode; `PeerAuthentication` STRICT (default in the chart).
- [ ] Gateway JWT validation enabled (`security.edgeJwt.enabled=true`, default).
- [ ] Edge rate limiting enabled (`gateway.rateLimits.enabled=true`, default) and limits reviewed for your traffic (`gateway.rateLimits.*`).
- [ ] NetworkPolicies enabled (default deny).
- [ ] Datastores reachable only from the cluster, with TLS and authentication.
- [ ] Secrets from a secret manager, not committed values files.
- [ ] Image signatures verified (cosign) by your admission controller, if you run one.
- [ ] Two-factor login for every admin and finance user.

## 5. Moving from a single server to Kubernetes

1. Prepare the cluster and datastores (sections 4.1 and 4.2) and install FoodStack with **the same JWT keys and PII key** from `/opt/foodstack/.env`, so existing logins and encrypted data keep working.
2. Announce a short maintenance window. On the single server: `./stop.sh server web backoffice` (datastores keep running).
3. `mongodump` all `foodstack_*` databases and restore them into the new MongoDB.
4. Copy media: `mc mirror` from MinIO to your S3 buckets.
5. `helm upgrade` with `--set maintenance.reindex=true` to rebuild search indices.
6. Switch DNS to the cluster's Gateway address.
7. Keep the old server stopped but intact for a week as a fallback.

## 6. Troubleshooting

| Symptom | Check |
|---|---|
| Server pod or container keeps restarting | `logs server`: Module-Switch prints every module's mode at startup and fails with a clear message on bad configuration |
| "Module X is off but Y needs it" at startup | A custom topology does not run a required module anywhere; add it to a Deployment |
| Login emails not arriving | SMTP settings; the notification delivery log in the admin panel shows failures |
| Orders stuck in `PLACED` | Payment webhooks not reaching `api.<domain>/api/v1/payment-webhooks/…`; check the gateway dashboard and proxy logs |
| Notifications or ledger lagging | Kafka consumer lag dashboard; outbox lag metric; dead-letter topics in the admin panel |
| Search results stale or empty | Rebuild search from the admin panel (Settings → Maintenance) |
| Elasticsearch will not start on a single server | `vm.max_map_count`; rerun `install.sh --check` |
| Disk full | `status.sh` shows usage; prune old backups and logs, then extend the disk |
