# FoodStack: Deployment Guide

| | |
|---|---|
| Status | v1. Docker Compose (one machine); Kubernetes (Helm) since M5, checked on a test cluster in every topology ([TDD](TDD.md) sections 17, 18 and 27) |
| Audience | The operator's DevOps or whoever installs and runs FoodStack. For step-by-step guides written for people without an operations background, see the [Operator handbook](handbook/README.md) |

## 1. Choose your path

| | Docker Compose (one machine) | Kubernetes (Helm) |
|---|---|---|
| Best for | Trying it, launch, pilots, one city, up to about 200 orders per hour | Growth, high availability, several cities, independent scaling |
| You need | One Linux server with Docker | A Kubernetes cluster, plus managed or operator-run datastores |
| Datastores | Included (MongoDB, Kafka, Redis, Elasticsearch) | Bring your own (recommended) or run them in-cluster for trials |
| Photos and files | Your own S3-compatible buckets (Amazon S3, Cloudflare R2, Backblaze B2) | The same |
| Topologies | Every module in one server | `monolith`, `split`, `microservices` or `custom`, switchable at any time |
| High availability | No (one machine) | Yes (replicas across nodes and zones) |
| Settings | `.env` and `config/application.yml` | `my-values.yaml` and Secrets |

Start with Docker Compose. Moving later is a data copy plus settings, never a code change: see the
[migration guide](migration-guide.md). Running modules as separate services on your own machines,
without Kubernetes, is also possible ([deploy/services](../deploy/services/README.md)).

Which module needs which datastore, outside service and setting:
[architecture 1.0](architecture/01-big-picture.md#10-what-each-service-needs).

## 2. Before you start (both paths)

### 2.1 Accounts and services

| Item | Needed for | Notes |
|---|---|---|
| Domain names | Everything | Three hostnames, for example `example.com` (customer app), `admin.example.com` (back office), `api.example.com` (API and login) |
| S3-compatible storage | Images, rider documents | **Required.** Two buckets (one readable by everyone), an access key with read and write on both, and a CORS rule for your two sites. Amazon S3, Cloudflare R2 and Backblaze B2 are tested ([handbook guide 12](handbook/12-storage.md)). The server checks them on every start and refuses to start until they work |
| Map services | Address search, map tiles, optional road distances | **Required.** Google Maps Platform (Geocoding API, Routes API) or any Nominatim-compatible search, plus any `{z}/{x}/{y}` tile service; OSRM optional for road distances. OpenStreetMap's public servers are allowed only in a trial on your own computer ([handbook guide 13](handbook/13-maps.md)) |
| SMTP account | Email notifications, login codes | Any provider; TLS on port 587 recommended |
| Firebase Cloud Messaging project | Push notifications | Service-account JSON key |
| Payment gateway account | Card payments | Only if cards are enabled; hosted payment page recommended at launch |

### 2.2 Secrets you will hold

You make these once, with `openssl` (the commands are in [deploy/compose](../deploy/compose/README.md#install-on-a-server) and [Kubernetes guide 6](kubernetes-guide.md#6-keys-and-secrets)).

| Secret | Purpose |
|---|---|
| JWT signing key (ES256) | Signs login tokens. Rotating it keeps the previous key valid until old tokens expire |
| Personal-data key (32 random bytes) | Encrypts names, emails, phones and addresses at rest. **Back it up separately; losing it makes that data unreadable** |
| Module-Switch internal token, service client secret | Protect module-to-module calls when modules are split |
| Datastore passwords | MongoDB, Redis, Elasticsearch, S3 access key, Kafka (if secured) |
| SMTP, FCM, payment gateway and maps credentials | External providers |

## 3. Docker Compose (one machine)

Everything is in [`deploy/compose`](../deploy/compose/README.md), whose README is the full guide:
trying it on your own computer, installing on a server, changing settings, backups, restore,
upgrades and the go-live checklist. In short:

```bash
curl -LO https://github.com/ilyaslabs/foodstack/releases/download/v1.0.0/foodstack-compose-1.0.0.zip
unzip foodstack-compose-1.0.0.zip && cd foodstack-compose-1.0.0
cp .env.example .env
# fill in .env (addresses, datastore passwords, map tiles) and config/application.yml (keys,
# first administrator, buckets, maps, email, payments)
docker compose up -d
```

| Task | How |
|---|---|
| Change a setting | Edit `config/application.yml`, then `docker compose restart server` |
| Status and logs | `docker compose ps`, `docker compose logs -f server` |
| Back up | `mongodump` from the `mongodb` container, nightly from cron, copied off the server ([handbook 20](handbook/20-backups.md)). Kafka, Redis and Elasticsearch need no backup |
| Upgrade | The new release's Compose files and Caddyfile, any new settings, a new `FOODSTACK_VERSION` in `.env`, then `docker compose pull && docker compose up -d` ([handbook 21](handbook/21-upgrades.md)) |
| Trial on your computer | `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d`, then http://localhost:8080 |

Server size: 4 vCPU, 8 GB memory and 100 GB SSD to start; 8 vCPU, 16 GB for a busy city. 64-bit
Linux (amd64 or arm64), Docker Engine 27+ with Compose, ports 80 and 443 open.

### 3.1 First sign-in checklist

Step by step: [handbook guides 17 to 19](handbook/README.md#start-here-your-first-launch).

1. Open `https://admin.example.com/admin/`, set up the authenticator app (required for administrators), then **Change password** in the account menu. Make your team's accounts under **Users**, **New staff account**.
2. **Branding:** name, tagline, logo, colour.
3. **Languages:** languages, default language, currency, timezone.
4. **Ordering:** cash and card (on the gateway's page or in card fields inside the app, and whether customers may save a card), service fee, tax, tips and how long stores have to accept an order.
5. **Messages:** reword any email, notification or push message in each of your languages, with a preview; a field left empty keeps FoodStack's text.
6. **Cities**, and per branch: pickup, and whether the platform's riders deliver for it (the branch's **Delivery** tab). Card payment, saved cards and branches collecting card payments themselves are on **Ordering**.
7. **Email log:** sign in once with "Email me a sign-in code" and check that the email shows as Sent.
8. **Delivery pricing** and **Rider pay**, if you run your own riders; otherwise stores deliver themselves.
9. **Stores:** onboard the first store and choose whether it may use operator delivery; let the owner set delivery mode, hours, zones and menu.
10. Place a test order end to end, then cancel it (**Cancel order** on the order, with a reason).

Everything about the business (branding, languages, currency, timezone, fees, commission, feature switches) is set in the back office, not in files. Each branch also has its own time zone, found from its location; the store's owner can change it on the branch page. Card payment also has a switch on the **Ordering** page: customers see it only when the gateway is set in the settings and the switch is on.

## 4. Kubernetes (Helm)

The [Kubernetes guide](kubernetes-guide.md) covers this in full: what gets installed, preparing the cluster, every chart setting, monitoring, upgrades, backups and troubleshooting. This section is the short version.

### 4.1 Cluster prerequisites

| Component | Version | Notes |
|---|---|---|
| Kubernetes | 1.31+ | Any CNCF-conformant distribution or managed service |
| Gateway API CRDs | v1.3+ | Standard channel |
| Istio | 1.30+, ambient mode | mTLS between all pods and per-request load balancing. istiod **must** run with `PILOT_JWT_ENABLE_REMOTE_JWKS=envoy` (below). The chart also works without Istio (`mesh.enabled=false`, any Gateway API implementation) |
| cert-manager | 1.16+ | Issues TLS certificates (for example with Let's Encrypt) |
| Metrics Server | | Needed for autoscaling |
| Nodes | 3+ across 2+ zones | Reference: 3 × 4 vCPU / 16 GB for `monolith` with managed datastores |

Install the Gateway API CRDs and Istio in ambient mode:

```bash
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.3.0/standard-install.yaml

helm repo add istio https://istio-release.storage.googleapis.com/charts && helm repo update
helm install istio-base istio/base -n istio-system --create-namespace --wait
# The gateway fetches the token keys itself and keeps retrying. Without this setting istiod
# fetches them once; if FoodStack is still starting at that moment, every signed-in request is
# refused ("Jwt verification fails") for up to 20 minutes.
helm install istiod istio/istiod -n istio-system --set profile=ambient \
  --set pilot.env.PILOT_JWT_ENABLE_REMOTE_JWKS=envoy --wait
helm install istio-cni istio/cni -n istio-system --set profile=ambient --wait
helm install ztunnel istio/ztunnel -n istio-system --wait
```

On k3s add `--set global.platform=k3s` to the istiod, istio-cni and ztunnel installs.

### 4.2 Datastores

Use managed services or operator-run clusters in production. Minimums for a reference production setup:

| Datastore | Production minimum | Notes |
|---|---|---|
| MongoDB | 3-member replica set, 7.0+ | Transactions require a replica set. FoodStack creates one database per module (`foodstack_<module>`) |
| Kafka | 3 brokers, 3.7+ (KRaft) | Topics are created by FoodStack with 24 partitions and replication factor 3 |
| Redis | Primary with a replica, or Redis Cluster, 7+ | Cache, coordination and the gateway's rate-limit counters |
| Elasticsearch | 3 nodes, 9.x | Search indices rebuildable from data |
| Object storage | S3 or compatible | Two buckets: `foodstack-public`, `foodstack-private` |

### 4.3 Try it first: a trial cluster

`values-dev.yaml` runs every datastore inside the cluster with small sizes, sends emails to Mailpit and serves plain HTTP on `foodstack.localhost` names. Not for real data: no backups, no high availability.

```bash
helm install foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 \
  -n foodstack --create-namespace -f https://raw.githubusercontent.com/ilyaslabs/foodstack/v1.0.0/k8s/helm/foodstack/values-dev.yaml
```

The notes printed at the end give the gateway address, the first administrator's password and the Mailpit command. Point `foodstack.localhost` and `admin.foodstack.localhost` at the gateway address in your hosts file, then open `http://admin.foodstack.localhost/admin/`.

### 4.4 Values for a real install

You make the server's five keys once, before the first install: the token signing key, the personal-data key, the internal Module-Switch token, the service client secret and the first administrator's password. The chart does not generate them on a real install; it stops until each is given. Put them in your own Secret named in `secrets.existingSecret` (recommended; commands in [Kubernetes guide 6](kubernetes-guide.md#6-keys-and-secrets)), or in the `secrets.*` placeholders of your values file. **Keep a copy of the personal-data key outside the cluster**: without it the stored personal data cannot be read.

```yaml
# my-values.yaml
topology: monolith            # monolith | split | microservices | custom

hosts:
  web: example.com
  admin: admin.example.com
  api: api.example.com

gateway:
  tls:
    certManager: { issuer: letsencrypt }   # a ClusterIssuer; or secretName: an existing TLS Secret

secrets:
  adminEmail: you@example.com
  existingSecret: foodstack-keys   # the five keys, made once (Kubernetes guide 6)

# Either the connection details here (the chart keeps them in a Secret)...
datastores:
  mongodb: { uri: "mongodb+srv://…", sharding: false }
  redis: { url: "rediss://:…@redis.example.com:6380" }
  kafka: { bootstrap: "kafka-1:9092,kafka-2:9092,kafka-3:9092", replicationFactor: 3 }
  elasticsearch: { uris: "https://es.example.com:9200", password: "…" }
  # Your own buckets (handbook guide 12); the server checks them on start and refuses to start
  # when a bucket is missing, not public, not writable or has no CORS rule for your two sites.
  s3:
    endpoint: https://s3.eu-west-1.amazonaws.com
    region: eu-west-1
    pathStyle: false                         # Amazon S3; true for R2 and B2
    publicBaseUrl: https://foodstack-public.s3.eu-west-1.amazonaws.com   # or your CDN
    accessKey: "…"
    secretKey: "…"
    publicBucket: foodstack-public
    privateBucket: foodstack-private
# ...or the credentials in an existing Secret (keys MONGODB_URI, REDIS_URL,
# ELASTICSEARCH_PASSWORD, S3_ACCESS_KEY, S3_SECRET_KEY), with kafka.bootstrap,
# elasticsearch.uris and the s3 addresses still given above:
#   datastores: { existingSecret: foodstack-datastores, … }

# Required (handbook guide 13); OpenStreetMap's public servers are refused.
maps:
  geocoding: { provider: google, countryCodes: pk }     # or nominatim with url
  routing: { provider: google }                         # none, osrm with url, or google
  tiles: { url: "https://tiles.example.com/{z}/{x}/{y}.png?key=…", attribution: "© Example © OpenStreetMap contributors" }

server:
  env:
    PAYMENT_GATEWAY: stripe
  # Secrets loaded whole into every server pod: SMTP_*, STRIPE_*, FCM_*, GOOGLE_MAPS_API_KEY
  # (Kubernetes guide 7.2)
  envFromSecrets: [foodstack-providers]
```

```bash
kubectl create namespace foodstack
kubectl -n foodstack create secret generic foodstack-providers \
  --from-literal=SMTP_HOST=smtp.example.com --from-literal=SMTP_USERNAME=… --from-literal=SMTP_PASSWORD=… \
  --from-literal=SMTP_AUTH=true --from-literal=SMTP_STARTTLS=true --from-literal=SMTP_FROM=orders@example.com \
  --from-literal=STRIPE_SECRET_KEY=… --from-literal=STRIPE_WEBHOOK_SECRET=… \
  --from-literal=GOOGLE_MAPS_API_KEY=… \
  --from-file=FCM_SERVICE_ACCOUNT=fcm.json   # the key file's JSON itself; a path works on one server only
```

### 4.5 Install and verify

```bash
helm install foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 \
  -n foodstack -f my-values.yaml

kubectl -n foodstack get pods,gateway,httproute,hpa
kubectl -n foodstack get gateway foodstack -o jsonpath='{.status.addresses[0].value}'   # point DNS here
```

The install runs migrations in a hook Job first, then starts the pods. Sign in at `https://admin.example.com/admin/` with the email in `secrets.adminEmail` and the password from the notes. The back office asks the administrator to set up an authenticator app on the first sign-in (two-step sign-in is required for the ADMIN and FINANCE roles). Then follow the first sign-in checklist in section 3.1.

Load test before you open to customers, from an address the rate limits allow (`tools/k6/browse.js` in the repository; it reads only):

```bash
docker run --rm -i -e BASE_URL=https://api.example.com -e LAT=31.52 -e LNG=74.34 \
  grafana/k6:1.8.1 run - < tools/k6/browse.js
```

It searches, opens stores and reads menus and delivery quotes with 50 users at the peak, and fails if more than 1 % of requests fail or a step's 95th percentile passes 300 ms.

### 4.5a Installing one service at a time

`helm install` starts every service together. To review the objects first and start them yourself, one service at a time in the order you choose, write them into one folder per service with `render.sh`, which comes inside the chart, and apply each folder with `kubectl`:

```bash
helm pull oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 --untar
bash foodstack/render.sh --out rendered -n foodstack -f my-values.yaml --set topology=split
```

```
rendered/
├─ 00-secrets      signing key, personal-data key, admin password, datastore addresses: do not commit
├─ 02-shared       gateway, routes, rate limits, mesh and network policies
├─ 03-migrations   the database migrations Job
├─ server-core     Deployment, Service, ConfigMap, ServiceAccount, autoscaler and budget of one service
├─ server-order    (one folder per server Deployment of the topology)
├─ ...
├─ web
└─ backoffice
```

Nothing is applied until you apply it. The prerequisites go first, then the services in any order:

```bash
kubectl create namespace foodstack
kubectl -n foodstack apply -f rendered/00-secrets
kubectl -n foodstack apply -f rendered/02-shared
kubectl -n foodstack delete job foodstack-migrations --ignore-not-found   # a new version needs a new Job
kubectl -n foodstack apply -f rendered/03-migrations
kubectl -n foodstack wait --for=condition=complete job/foodstack-migrations --timeout=30m

kubectl -n foodstack apply -f rendered/server-core
kubectl -n foodstack rollout status deployment/foodstack-core
kubectl -n foodstack apply -f rendered/server-order                          # and so on, one at a time
kubectl -n foodstack get all -l foodstack.ilyaslabs.io/part=server-order     # one service's objects
```

- Start the service that runs `identity` (`server-core` in the monolith and split topologies, `server-identity` in microservices) before the others: every service checks sign-in tokens against it. A service whose neighbours are not running yet starts, and its calls to them fail until they are; their routes answer 503 meanwhile.
- `00-secrets` holds the keys from your `secrets.*` values, the same on every render. With `secrets.existingSecret` (4.4) there is no key file at all. Only trial values generate keys; for them `render.sh` reads the Secret already in the cluster, and `--offline` makes new ones.
- To upgrade, render again with the new chart version, run the migrations Job again, then apply the service folders in your order. A folder you have not applied yet keeps running the old version, so apply them all before you leave the install; every service is tested only together at one version.
- An install is managed either by Helm or by `kubectl` from these folders, never both: Helm refuses objects it did not create, and `helm upgrade` would undo changes made with `kubectl`. You can still render the folders to review a Helm upgrade before you run it.

### 4.6 Scaling and topologies

How the load is split between machines, and the stages of growth, are drawn in [architecture 8](architecture/08-scaling.md). Step-by-step runbooks for each step are in the [Operator handbook](handbook/README.md#growing), starting with [knowing when to grow](handbook/30-when-to-grow.md).

| Goal | Change |
|---|---|
| More capacity, same shape | Raise `server.deployments.<name>.autoscaling.max` (or `replicas`) |
| Scale the busiest modules independently | `helm upgrade … --set topology=split` |
| One Deployment per module | `--set topology=microservices` |
| Your own grouping | `topology: custom` with `server.deployments` listing modules per Deployment ([TDD 18.2](TDD.md#182-module-topology-one-setting-any-shape)) |

A topology change rolls one Deployment at a time; calls between modules are retried while targets start, so traffic keeps flowing. **No data moves** when you change topology.

Kafka consumers: the chart keeps `replicas × listener concurrency ≤ 24` per module and warns if you exceed it, since extra consumers would sit idle. Listener threads per module are `foodstack.kafka.concurrency.<module>` (default 3).

Background work needs no leader: the outbox relay, the dispatch matcher and the accept-timeout sweep split 64 work buckets between the replicas that run them, so adding a replica adds capacity and losing one hands its share to the others within seconds ([TDD 7.3](TDD.md#73-work-buckets-background-work-on-every-replica)).

### 4.6a Growing the other datastores

| Store | How to grow | FoodStack setting |
|---|---|---|
| Redis | A primary with replicas and Sentinel for failover, or Redis Cluster for more memory and throughput | One of `SPRING_DATA_REDIS_URL`, `SPRING_DATA_REDIS_SENTINEL_MASTER` + `SPRING_DATA_REDIS_SENTINEL_NODES`, or `SPRING_DATA_REDIS_CLUSTER_NODES` (with `SPRING_DATA_REDIS_PASSWORD`). Nothing else changes: every command uses one key, and live messages switch to sharded pub/sub on a cluster |
| Elasticsearch | Add nodes | `foodstack.search.auto-expand-replicas` (`0-1` by default, `0-all` to keep a copy on every node) applies to the live index on the next start. More primary shards (`foodstack.search.shards`) take effect on the next index rebuild |
| Kafka | Add brokers; set replication factor 3 | `KAFKA_REPLICATION_FACTOR=3`. Topics start with 24 partitions; adding partitions later re-maps keys, so choose the count early |
| CDN (optional) | Put a CDN or the Gateway's cache in front of the API host | Live branch pages and menus answer with `Cache-Control: public, s-maxage=30` and an ETag, so a shared cache serves most views for up to 30 s; everything else is `no-store` or private |

### 4.7 Moving one module's data to its own MongoDB cluster

Only when the shared cluster is the bottleneck or you need isolation. Kubernetes only: the single-server install keeps every module's database on its one MongoDB. Coming from one server, move to Kubernetes first ([handbook 40](handbook/40-move-to-kubernetes.md), all `foodstack_*` databases onto one cluster), split the modules if you want ([handbook 42](handbook/42-split-modules.md), no data moves), then move a module's database as below.

1. Create the new cluster (replica set).
2. Stop every server Deployment (note the replica counts first): the module's data must not change while it is copied. Plan 15 to 60 minutes, depending on its size.
3. Copy the whole module database: `mongodump --db foodstack_order --archive | mongorestore --archive --drop` to the new cluster. The copy carries the module's Liquibase history (`order_databasechangelog`) and its outbox and inbox, so nothing is migrated twice and no event is lost or sent again.
4. Create a secret with the new URI and set it for that module:
   ```yaml
   server:
     moduleDatastores:
       order: { mongodbUriSecret: order-mongodb }
   ```
5. `helm upgrade`. The migrations Job runs the module's changelog against the new cluster (it finds nothing to do), and the upgrade sets the replica counts back.
6. After verifying, drop `foodstack_order` from the old cluster.

Full steps, checks and undo: [handbook 45](handbook/45-module-database.md).

### 4.8 Growing MongoDB: replicas and shards

- **More replicas:** add members to the replica set; no FoodStack change.
- **Sharding:** convert the replica set into the first shard of a sharded cluster, point `MONGODB_URI` at `mongos`, and set `datastores.mongodb.sharding: true`. On the next `helm upgrade` the migrations job indexes and shards each collection on the key listed in [TDD 6.3](TDD.md#63-designed-for-sharding), including collections that already hold data. FoodStack's CI already tests every query against a sharded cluster. Steps: [handbook runbook 44](handbook/44-mongodb-sharding.md).

### 4.9 Observability

```bash
helm install foodstack-observability oci://ghcr.io/ilyaslabs/charts/foodstack-observability \
  --version 1.0.0 -n foodstack-observability --create-namespace
helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 -n foodstack \
  --reuse-values --set server.tracing.enabled=true
```

The observability chart runs one small pod each of Prometheus, Grafana, Loki, Tempo and Alertmanager (off unless `alertmanager.enabled=true`), and an OpenTelemetry Collector on every node that ships the FoodStack pods' logs to Loki and takes their traces. Install it in the `foodstack-observability` namespace, so the FoodStack chart's default tracing endpoint finds it. Prometheus joins the Istio mesh to read the server's metrics (`ambient: true`, the default; set it to false on a cluster without Istio).

Grafana is not published on the internet: `kubectl -n foodstack-observability port-forward svc/foodstack-grafana 3000:3000`, then sign in as `admin` with the password in the Secret `foodstack-grafana`. The **FoodStack** folder has three dashboards: overview (traffic, errors and latency per Deployment, heap, database and Redis time), orders and delivery (the order funnel, time to find a rider, pickup to delivery) and events (outbox backlog and age, consumer lag, handler failures, failed events). Logs are under Explore, Loki; a log line's trace id opens the trace in Tempo.

Alerts, with thresholds under `prometheus.alerts`:

| Alert | Fires when |
|---|---|
| FoodStackPodDown | A server pod has not answered for 5 minutes |
| FoodStackErrors | More than 2 % of a Deployment's requests end in a server error for 10 minutes |
| FoodStackSlow | A Deployment's API requests take over 1 s at the 95th percentile for 10 minutes |
| FoodStackOutboxStuck | A module's oldest unpublished event is over 2 minutes old |
| FoodStackDeadLetters | Any event failed for good in the last 15 minutes (replay it on the **Failed events** page) |
| FoodStackConsumerLag | A consumer group is over 10,000 events behind for 10 minutes |
| FoodStackMemory | A pod's heap is over 90 % full after garbage collection for 10 minutes |
| CertificateExpiring | A cert-manager certificate expires within 14 days |

To send them by email or chat, set `alertmanager.enabled=true` and its `alertmanager.config` (route and receivers). To use your own monitoring instead, scrape pods annotated `prometheus.io/scrape` (port 8082, `/actuator/prometheus`; from inside the mesh) and set `server.tracing.endpoint` to your collector.

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

- [ ] Pods in Istio ambient mode with `mesh.mtls: STRICT` (the chart's default).
- [ ] istiod runs with `PILOT_JWT_ENABLE_REMOTE_JWKS=envoy` (section 4.1).
- [ ] Gateway token check on (`gateway.jwt.enabled=true`, the default).
- [ ] Edge rate limiting on (`gateway.rateLimit.enabled=true`, the default) and the per-address limits in `gateway.rateLimit.limits` reviewed for your traffic.
- [ ] NetworkPolicies on (`networkPolicies.enabled=true`, the default).
- [ ] The five keys (above all the personal-data key) kept outside the cluster.
- [ ] Datastores reachable only from the cluster, with TLS and authentication.
- [ ] Secrets from a secret manager, not committed values files.
- [ ] Image signatures verified (cosign) by your admission controller, if you run one.
- [ ] Two-step sign-in for every back-office account that can change money or settings (required for ADMIN and FINANCE; others turn it on under **Two-step sign-in** in the account menu).

## 5. Moving between setups

Docker Compose to Kubernetes, Compose to separate services, Kubernetes monolith to split or
microservices, and back: the [migration guide](migration-guide.md) has each path, step by step.

## 6. Troubleshooting

| Symptom | Check |
|---|---|
| Server pod or container keeps restarting | `logs server`: Module-Switch prints every module's mode at startup and fails with a clear message on bad configuration |
| "Module X is off but Y needs it" at startup | A custom topology does not run a required module anywhere; add it to a Deployment |
| Login emails not arriving | SMTP settings; the **Email log** page shows each email with its error, and **Send again** retries it |
| Card orders stuck in "Waiting for payment" | Payment webhooks not reaching `api.<domain>/api/v1/payment-webhooks/stripe`; check the webhook's deliveries in the Stripe dashboard and the proxy logs. Unpaid pages expire after 30 minutes and the order fails |
| Orders rejected with "did not answer in time" | Nobody accepted within the time set on the **Ordering** page; keep the store's **Live orders** page open with sound on |
| Notifications or ledger lagging | The **FoodStack events** dashboard (outbox age, consumer lag); events that failed for good wait on **Settings, Failed events** in the back office: fix the cause, then **Replay** |
| Kubernetes: every signed-in request answers 401 "Jwt verification fails" | istiod could not fetch the token keys while FoodStack was starting. Run istiod with `PILOT_JWT_ENABLE_REMOTE_JWKS=envoy` (section 4.1); until then, restarting istiod fetches them again |
| Kubernetes: 503 "remote connection failure" after a node restarted | Pods that started before Istio's CNI agent was ready are not in the mesh: `kubectl -n foodstack rollout restart deploy,statefulset` |
| Kubernetes: Prometheus shows the server pods down ("EOF") | Prometheus is outside the mesh, and the mesh only takes mutual TLS. Keep the observability chart's `ambient: true`, or label your Prometheus pod `istio.io/dataplane-mode: ambient` |
| Search results stale or empty | Only live branches with a delivery area (or pickup) appear. Every branch is re-read once a day; to rebuild now, call `POST /api/v1/search/reindex` with an admin token (202), or delete the `foodstack_search_stores_v*` index and restart: a missing index is created and filled on start |
| Elasticsearch will not start (Docker Compose) | `docker compose logs elasticsearch`; usually too little memory for `ES_HEAP`: lower it in `.env` and `docker compose up -d` |
| Disk full | `df -h /` and `docker system df`; prune old backups and unused images (`docker image prune`), then extend the disk |
| More symptoms, in plain language | [Handbook troubleshooting](handbook/90-troubleshooting.md) |
