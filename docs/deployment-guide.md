# FoodStack: Deployment Guide

| | |
|---|---|
| Status | v1. Single server since M0; Kubernetes (Helm) since M5, checked on a test cluster in every topology ([TDD](TDD.md) sections 17, 18 and 27) |
| Audience | The operator's DevOps or whoever installs and runs FoodStack. For step-by-step guides written for people without an operations background, see the [Operator handbook](handbook/README.md) |

## 1. Choose your path

| | Single server (unit deployer) | Kubernetes (Helm) |
|---|---|---|
| Best for | Launch, pilots, one city, up to about 200 orders per hour | Growth, high availability, several cities, independent scaling |
| You need | One Linux server with Docker | A Kubernetes cluster, plus managed or operator-run datastores |
| Datastores | Included and installed for you (MongoDB, Kafka, Redis, Elasticsearch) | Bring your own (recommended) or run them in-cluster for trials |
| Photos and files | Your own S3-compatible buckets (Amazon S3, Cloudflare R2, Backblaze B2) | The same |
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
| Map services | Address search, map tiles, optional road distances | **Required.** Google Maps Platform (Geocoding API, Routes API) or any Nominatim-compatible search, plus any `{z}/{x}/{y}` tile service; OSRM optional for road distances. OpenStreetMap's public servers are allowed only in a `--local` trial ([handbook guide 13](handbook/13-maps.md)) |
| S3-compatible storage | Images, rider documents | **Required.** Two buckets (one readable by everyone), an access key with read and write on both, and a CORS rule for your two sites. Amazon S3, Cloudflare R2 and Backblaze B2 are tested ([handbook guide 12](handbook/12-storage.md)). The server checks them on every start and refuses to start until they work |

### 2.2 Secrets you will hold

Generated for you on a single server; created by you on Kubernetes.

| Secret | Purpose |
|---|---|
| JWT signing key pair (ES256) | Signs login tokens. Rotating it keeps the previous key valid until old tokens expire |
| PII encryption key (32 random bytes) | Encrypts emails and phone numbers at rest. **Back it up separately; losing it makes that data unreadable** |
| Module-Switch internal token | Protects module-to-module calls |
| Datastore passwords | MongoDB, Redis, Elasticsearch, S3 access key, Kafka (if secured) |
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

# 2. Install into a directory of your choice (data, logs and backups live there), with the
#    settings file holding your buckets and map provider (handbook guides 12 and 13)
sudo ./install.sh /opt/foodstack \
  --domain example.com \
  --admin-domain admin.example.com \
  --api-domain api.example.com \
  --admin-email ops@example.com \
  --settings ~/foodstack-settings.env
```

The settings file holds `KEY=VALUE` lines: `S3_ENDPOINT`, `S3_REGION`, `S3_PUBLIC_BUCKET`, `S3_PRIVATE_BUCKET`, `S3_PUBLIC_BASE_URL`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `GEOCODING_PROVIDER` (and `GEOCODING_URL` or `GOOGLE_MAPS_API_KEY`), `GEOCODING_COUNTRY_CODES`, `MAP_TILE_URL`, `MAP_ATTRIBUTION`, and optionally `ROUTING_PROVIDER` / `ROUTING_URL`. Values can also come from the environment, or as answers when the installer asks for what is missing.

`install.sh`:

1. Checks Docker, Compose, CPU, memory, disk and that ports 80 and 443 are free.
2. Elasticsearch (store search) is part of the stack and runs without memory-mapped files, so no kernel setting is needed. When run as root on Linux, `install.sh` also raises `vm.max_map_count` to 262144, which Elasticsearch prefers for large indices.
3. Creates `/opt/foodstack/{logs,config,backups}`. Service data lives in named Docker volumes (`foodstack_mongodb`, `foodstack_kafka`, …), so no folder ownership setup is needed.
4. Generates strong passwords, the JWT key pair, the PII key and the internal token into `/opt/foodstack/.env` (permissions 600).
5. Copies the application, web apps and configuration.
6. Starts the datastores, waits for them to be healthy, runs database migrations once, then starts the application and the web apps.
7. Optionally installs a systemd unit so FoodStack starts on boot (`--systemd`).
8. Prints the first administrator's email and generated password once (also kept in `.env` as `FOODSTACK_ADMIN_PASSWORD`). Change it after the first sign-in (account menu, **Change password**).

For a quick trial on your own computer (Linux, or macOS/Windows with Docker Desktop), without DNS, certificates or sudo: `./install.sh ~/foodstack --local`, then open `http://foodstack.localhost:8080/admin/` and sign in as `admin@foodstack.localhost` / `foodstack-local` (fixed for trials; a `--domain` install generates a random password).

`sudo` is only needed to install into a folder your user can't write to (like `/opt`) or with `--systemd`. If an install stops part way (for example a failed image download), fix the cause and run the same command again: it removes the unfinished attempt and starts over. `./uninstall.sh` removes an install and its data volumes, keeping `backups/`.

### 3.3 Configure

Edit `/opt/foodstack/.env`, then run `./restart.sh server`. The main settings:

| Variable | Example | Notes |
|---|---|---|
| `FOODSTACK_DOMAIN`, `FOODSTACK_ADMIN_DOMAIN`, `FOODSTACK_API_DOMAIN` | `example.com` | Set by `install.sh` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `SMTP_FROM`, `SMTP_FROM_NAME` | `smtp.example.net`, `587`, …, `true`, `true`, `orders@example.com`, `Your brand` | Order emails, sign-in codes and password reset. While `SMTP_HOST` or `SMTP_FROM` is empty nothing is emailed: customers still see updates in the app and the **Email log** page shows each message as "Not sent" |
| `PAYMENT_GATEWAY` | `none` or `stripe` | Card payments. `none` means cash only. `sandbox` is a test page that charges nothing, for development; the server refuses it when `FOODSTACK_ENVIRONMENT=production` |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | `sk_live_…`, `whsec_…` | With `PAYMENT_GATEWAY=stripe`. In the Stripe dashboard add a webhook endpoint `https://api.example.com/api/v1/payment-webhooks/stripe` for `checkout.session.completed`, `checkout.session.expired`, `checkout.session.async_payment_succeeded` and `checkout.session.async_payment_failed`, and copy its signing secret |
| `STRIPE_PUBLISHABLE_KEY` | `pk_live_…` | Only for card fields inside the app (Ordering page: "In card fields inside the app"). The fields are Stripe's own secure frame, so card numbers never reach your server (PCI SAQ A). Without the key customers are sent to Stripe's page. Saved cards (Ordering page, off by default) need no extra key |
| `FOODSTACK_IDENTITY_DELETION_GRACE` | `P14D` | How long after a customer asks to delete their account it is deleted; signing in again before then cancels it. Orders stay without the customer's name, phone or address, and reviews show "Deleted user" |
| `FOODSTACK_WEB_URL` | `https://example.com` | Set by `install.sh`. Where customers come back to after paying, and the links in emails |
| `FCM_SERVICE_ACCOUNT`, `FCM_WEB_CONFIG`, `FCM_VAPID_KEY` | `/config/push/fcm.json`, `{"apiKey":"…","projectId":"…","messagingSenderId":"…","appId":"…"}`, `BK…` | Push to riders (new jobs) and customers (order updates). Create a Firebase project, save its service account key as `/opt/foodstack/config/push/fcm.json` (the server sees it as `/config/push/fcm.json`), and copy the web app config (one line) and the Web Push key pair from the Firebase console. While `FCM_SERVICE_ACCOUNT` is empty nothing is pushed and everyone still sees updates in the app |
| `S3_ENDPOINT`, `S3_REGION`, `S3_PATH_STYLE`, `S3_PUBLIC_BUCKET`, `S3_PRIVATE_BUCKET`, `S3_PUBLIC_BASE_URL`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | `https://s3.eu-west-1.amazonaws.com`, `eu-west-1`, `false`, …, `https://cdn.example.com` | Your buckets (set by `install.sh` from `--settings`). `S3_PUBLIC_BASE_URL` is where browsers load the public bucket's files. On start the server writes a test file to each bucket, reads the public one anonymously and checks CORS for your two sites, and stops with "Storage check failed: …" naming what to fix. `MEDIA_ORIGINS` (derived) lets the back office load and upload there |
| `ROUTING_PROVIDER`, `ROUTING_URL` | `none`, `osrm` or `google`, `http://osrm.internal:5000` | Road distance and driving time for delivery charges, dispatch and live ETAs. `none` uses the straight-line distance times a road factor; `osrm` asks an OSRM server you run; `google` uses the Routes API with `GOOGLE_MAPS_API_KEY` |
| `GEOCODING_PROVIDER`, `GEOCODING_URL`, `GEOCODING_COUNTRY_CODES`, `GOOGLE_MAPS_API_KEY` | `google` or `nominatim`, `https://search.example-provider.com`, `pk`, `AIza…` | Address search for customers and store onboarding, limited to your country. `google` uses the Geocoding API; `nominatim` any Nominatim-compatible service at `GEOCODING_URL`. The public OpenStreetMap Nominatim is used only by `--local` trials |
| `MAP_TILE_URL`, `MAP_TILE_ORIGIN`, `MAP_ATTRIBUTION` | `https://tiles.example-provider.com/{z}/{x}/{y}.png?key=…`, `https://tiles.example-provider.com`, `© Provider © OpenStreetMap contributors` | Map tiles in the back office (delivery zones) and live tracking, from a hosted provider or your own tile server. `install.sh` derives `MAP_TILE_ORIGIN`; if you change the host later, set it to the scheme and host of `MAP_TILE_URL`, because the page's security policy only loads tiles from there |
| `ELASTICSEARCH_URIS`, `ELASTICSEARCH_USERNAME`, `ELASTICSEARCH_PASSWORD` | set by `install.sh` | Point at an external Elasticsearch 9 cluster if you run one; the index is rebuilt from store data on start |
| `JAVA_HEAP`, `ES_HEAP` | `4g`, `2g` | Sized by `install.sh` from server memory |
| `FOODSTACK_REFRESH_COOKIE_SECURE` | `true` | Keep `true`. Only a plain-HTTP trial (`--local`) sets `false`, or browsers would drop the sign-in cookie |
| `FOODSTACK_TRACING_EXPORT`, `FOODSTACK_OTLP_ENDPOINT`, `FOODSTACK_TRACING_SAMPLING` | `false`, `http://collector:4318/v1/traces`, `0.1` | Send traces to an OpenTelemetry collector (Jaeger, Tempo, a vendor). Off by default; a trace follows a request through every module and through Kafka events |

Everything else (branding, languages, currency, timezone, fees, commission, feature switches) is set in the admin panel after login. Each branch also has its own time zone, found from its location (useful in countries with several zones); the store's owner can change it on the branch page. Card payment also has a switch on the back office **Ordering** page: customers see it only when the gateway is set here and the switch is on.

### 3.4 First login checklist

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

- `backup.sh` writes `backups/<timestamp>/` with a MongoDB dump (all `foodstack_*` databases) and the configuration including `.env`. Photos and files live in your buckets: turn on bucket versioning at your provider instead.
- Schedule it daily with cron (`--systemd` only starts FoodStack at boot; it installs no backup timer), and **copy backups off the server** (for example to S3 or another machine). Keep the PII key and `.env` in a password manager as well. [Handbook guide 20](handbook/20-backups.md) has the cron line, the off-server copy and the restore onto a new server.
- Kafka, Redis and Elasticsearch need no backup: events are delivered from MongoDB's outbox, Redis is a cache, and search is rebuilt from the data.
- Restore: `./restore.sh backups/<timestamp>` stops the application, restores MongoDB, clears Redis and the search index (both only copies), and starts again; search refills on start. It does not restore `.env`: on a new server, install first, then copy `FOODSTACK_PII_KEY` and `FOODSTACK_SIGNING_KEYS` (not the whole file: the datastore passwords differ) from the old `.env` before restoring.
- Test a restore on a spare server at least once before going live.

### 3.7 Upgrades

1. Read the release notes for the new version.
2. `./upgrade.sh unit-deployer-<new>.zip` takes a backup, replaces the application, web apps and compose file, runs migrations, and restarts with a short downtime (usually under a minute).
3. If something goes wrong: `./upgrade.sh --rollback` restores the previous version's files (not the data; the pre-upgrade backup stays in `backups/` for `restore.sh` if the release notes require it).

Migrations are always backward compatible with the previous release, so a rollback of the application does not require a data restore unless the release notes say so.

### 3.8 Hardening checklist

- [ ] Only ports 22, 80 and 443 open; SSH by key only, from known IPs.
- [ ] OS security updates automatic (`unattended-upgrades` or equivalent).
- [ ] Two-step sign-in for every back-office account that can change money or settings (required for ADMIN and FINANCE; others turn it on under **Two-step sign-in** in the account menu).
- [ ] Daily off-server backups, and one test restore done.
- [ ] Real SMTP, maps and payment credentials set; demo map services not used.
- [ ] `FOODSTACK_ENVIRONMENT=production` (blocks the `test-seed` demo data).
- [ ] Disk usage alert at 80% (`status.sh` reports it).
- [ ] The web app and the API on the same site (for example `example.com` and `api.example.com`), so the sign-in cookie is sent; `FOODSTACK_REFRESH_COOKIE_SECURE=true`.

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

No cluster yet? The [Kubernetes playground](../playground/k8s/README.md) runs FoodStack from the source tree on an EKS cluster on your own computer ([Floci](https://floci.io), set up with the usual `aws` and `kubectl` commands), with the datastores as Docker containers beside it, in any topology: `playground/k8s/up.sh --version 1.0.0 --topology split`.

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
  # Secrets loaded whole into every server pod: the settings of section 3.3 (SMTP_*, STRIPE_*,
  # FCM_*, GOOGLE_MAPS_API_KEY)
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

The install runs migrations in a hook Job first, then starts the pods. Sign in at `https://admin.example.com/admin/` with the email in `secrets.adminEmail` and the password from the notes. The back office asks the administrator to set up an authenticator app on the first sign-in (two-step sign-in is required for the ADMIN and FINANCE roles). Then follow the first-login checklist in section 3.4.

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

Only when the shared cluster is the bottleneck or you need isolation.

1. Create the new cluster (replica set).
2. Copy the module's database with near-zero downtime using MongoDB's live sync tool, or with a short maintenance window: `mongodump --db foodstack_order` then `mongorestore` to the new cluster.
3. Create a secret with the new URI and set it for that module:
   ```yaml
   server:
     moduleDatastores:
       order: { mongodbUriSecret: order-mongodb }
   ```
4. `helm upgrade`. Only the Deployment running `order` restarts.
5. After verifying, drop `foodstack_order` from the old cluster.

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

## 5. Moving from a single server to Kubernetes

Step by step, with every command: [handbook runbook 40](handbook/40-move-to-kubernetes.md). In short:

1. Prepare the cluster and datastores (sections 4.1 and 4.2). Put **the same signing key and PII key** from `/opt/foodstack/.env` into a Secret (with new `FOODSTACK_INTERNAL_TOKEN`, `FOODSTACK_SERVICE_CLIENT_SECRET` and `FOODSTACK_ADMIN_PASSWORD`) and name it in `secrets.existingSecret`, so existing logins and encrypted data keep working.
2. Announce a short maintenance window. On the single server: `./stop.sh proxy web backoffice server` (datastores keep running), then `./backup.sh`.
3. `mongorestore --nsInclude 'foodstack_*.*'` the backup's `mongodb.archive.gz` into the new MongoDB.
4. `helm install`, with `datastores.s3` and `maps` holding the same values as the server's `.env`: the buckets stay where they are, so photos need no copy. the **same version** the server runs. Migrations find nothing to do; search is empty in the new Elasticsearch, so the server creates the index and fills it on start.
5. Test through your hosts file, then switch DNS to the cluster's Gateway address.
6. Keep the old server stopped but intact for a week as a fallback.

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
| Elasticsearch will not start on a single server | `./logs.sh elasticsearch`; usually too little memory for `ES_HEAP`: lower it in `.env` and `./restart.sh elasticsearch` |
| Disk full | `status.sh` shows usage; prune old backups and logs, then extend the disk |
| More symptoms, in plain language | [Handbook troubleshooting](handbook/90-troubleshooting.md) |
