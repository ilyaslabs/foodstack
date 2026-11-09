# FoodStack on Kubernetes

This guide installs FoodStack on a Kubernetes cluster and runs it in production, with every command and every setting. It is written for the person who runs the cluster: you know `kubectl` and `helm`, and you have (or can get) a cluster and managed datastores.

- Never used Kubernetes? Start on one server ([deployment guide 3](deployment-guide.md#3-single-server-unit-deployer)) and move later ([handbook 40](handbook/40-move-to-kubernetes.md)): all data moves with you.
- Only want to look at it? [Section 4](#4-try-it-first-a-trial-install) runs everything inside a cluster in one command, and the [Kubernetes playground](../playground/k8s/README.md) does it on your own computer.

Contents

1. [What gets installed](#1-what-gets-installed)
2. [Before you start](#2-before-you-start)
3. [Prepare the cluster](#3-prepare-the-cluster)
4. [Try it first: a trial install](#4-try-it-first-a-trial-install)
5. [Datastores, buckets and maps](#5-datastores-buckets-and-maps)
6. [Keys and Secrets](#6-keys-and-secrets)
7. [Write your values file](#7-write-your-values-file), and [your deployment repository](#74-your-deployment-repository)
8. [Install](#8-install)
9. [Check it works](#9-check-it-works)
10. [Install one service at a time (optional)](#10-install-one-service-at-a-time-optional)
11. [Choose and change the topology](#11-choose-and-change-the-topology)
12. [Scaling](#12-scaling)
13. [Monitoring, logs and alerts](#13-monitoring-logs-and-alerts)
14. [Upgrades and rollback](#14-upgrades-and-rollback)
15. [Backups and disaster recovery](#15-backups-and-disaster-recovery)
16. [Security checklist](#16-security-checklist)
17. [Uninstall](#17-uninstall)
18. [Every chart setting](#18-every-chart-setting)
19. [Troubleshooting](#19-troubleshooting)

---

## 1. What gets installed

FoodStack ships three container images and two Helm charts, all with the same version number.

| What | Where | What it is |
|---|---|---|
| Server image | `docker.io/ilyasdotdev/ilyaslabs-foodstack:server-<version>` | The whole Java backend. It contains all 15 modules; settings choose which modules a pod runs, so one image serves every topology |
| Web image | `…:web-<version>` | The customer app (Angular with server-side rendering, port 4000) |
| Back-office image | `…:backoffice-<version>` | The admin, store and rider app (static files on nginx, port 8080) |
| `foodstack` chart | `oci://ghcr.io/ilyaslabs/charts/foodstack` | Everything FoodStack needs in the cluster |
| `foodstack-observability` chart | `oci://ghcr.io/ilyaslabs/charts/foodstack-observability` | Optional: Prometheus, Grafana, Loki, Tempo, Alertmanager, OpenTelemetry Collector |

The images are also on `ghcr.io/ilyaslabs/foodstack` with the same tags. Each release is listed on [GitHub Releases](https://github.com/ilyaslabs/foodstack/releases).

### What the `foodstack` chart creates

With the release name `foodstack` (used throughout this guide), every object's name starts with `foodstack`.

| Object | Name | Purpose |
|---|---|---|
| Deployment, Service, ServiceAccount, ConfigMap, HPA, PDB per server group | `foodstack-core`, and in other topologies `foodstack-order`, `foodstack-dispatch`, … | The backend. Each group runs its modules locally and calls the others over the internal port 8081 |
| Deployment, Service, HPA, PDB | `foodstack-web`, `foodstack-backoffice` | The two apps |
| Job (Helm hook, before install and every upgrade) | `foodstack-migrations` | Creates and updates every module's database (Liquibase), then exits |
| Secret (Helm hook, kept on uninstall) | `foodstack-secrets` | The keys you give in `secrets.*`: token signing key, personal-data key, internal token, service client secret, first admin password ([section 6](#6-keys-and-secrets)). Not made with `secrets.existingSecret` |
| Secret | `foodstack-datastores` | Datastore addresses and passwords from your values |
| Gateway (Gateway API) | `foodstack` | The public entry: ports 80 and 443, TLS, all three host names. Istio creates its pods and the Service `foodstack-istio` (a cloud load balancer) |
| Gateway | `foodstack-internal` | A cluster-only entry with the same routes, used by the web app's server-side rendering. No rate limits |
| HTTPRoutes | `foodstack-api`, `foodstack-apps`, … | Send each API path to the Deployment that owns that module; `/admin/` to the back office; the rest to the web app; HTTP to HTTPS |
| Certificate (cert-manager) | `foodstack` | When `gateway.tls.certManager.issuer` is set |
| RequestAuthentication | `foodstack-edge-jwt` | The gateway refuses forged or expired access tokens before they reach a pod |
| Rate-limit Deployment, EnvoyFilter, ConfigMap | `foodstack-ratelimit` | Per-client-IP limits at the gateway, counters in your Redis |
| Waypoint Gateway, PeerAuthentication, AuthorizationPolicies, DestinationRules | `foodstack-waypoint`, … | Istio ambient: mutual TLS between all pods, per-request load balancing, and only FoodStack's own pods may call the internal port |
| NetworkPolicies | `foodstack-server`, `foodstack-frontends`, `foodstack-ratelimit` | Which pods may open connections to which (incoming only) |

The 15 modules: `identity settings media geo store search promotion cart payment order rider dispatch notification ledger review`.

### How a request travels

```
Browser / rider phone
   │ HTTPS (example.com, admin.example.com, api.example.com)
   ▼
Cloud load balancer ── Service foodstack-istio
   ▼
Gateway "foodstack" (Envoy): TLS, edge rate limit, edge token check
   │  /api/v1/orders → foodstack-order   /api/v1/stores → foodstack-core   /admin/ → backoffice   / → web
   ▼
Waypoint (least-request load balancing) ── ztunnel mTLS on every node
   ▼
Server pods ── Module-Switch calls between groups on port 8081
   │
   ├── MongoDB (one database per module)   ├── Kafka (events)   ├── Redis (cache, locks, live updates)
   ├── Elasticsearch (store search)        └── S3 buckets (photos, private files)
```

---

## 2. Before you start

Tick each line before installing. Every item links to where it is explained.

**Cluster and tools**

- [ ] A Kubernetes cluster, 1.31 or newer, 3 or more nodes across 2 or more zones ([3.1](#31-cluster-size)).
- [ ] On your computer: `kubectl` (matching the cluster), `helm` 3.14+, `openssl`.
- [ ] Permission to create cluster-wide objects (CRDs, the `istio-system` namespace), or someone who can install [section 3](#3-prepare-the-cluster) for you.

**Outside the cluster**

- [ ] A domain, and access to its DNS records ([handbook 11](handbook/11-domain-and-dns.md)). Three names, for example `example.com` (customers), `admin.example.com` (back office), `api.example.com` (API). They may also be one name.
- [ ] MongoDB 7.0+ replica set, Kafka 3.7+, Redis 7+, Elasticsearch 9.x, all reachable from the pods ([5.1](#51-datastores)).
- [ ] Two S3-compatible buckets, public and private, with CORS for your two sites ([5.2](#52-buckets)).
- [ ] A maps provider for address search and map tiles ([5.3](#53-maps)).
- [ ] An SMTP account for emails (optional at first: without it nothing is emailed, and customers still see updates in the app).
- [ ] Optional: a Stripe account for card payments ([handbook 16](handbook/16-card-payments.md)); a Firebase project for push notifications.

---

## 3. Prepare the cluster

Do this once per cluster. Everything here is shared by every app on the cluster, not only FoodStack.

### 3.1 Cluster size

Resource requests at the minimum replica count, with the chart's defaults (`values-split.yaml` and `values-microservices.yaml` sizes for those topologies). Add about 1 vCPU and 2 GB per node for Kubernetes, Istio and monitoring.

| Topology | Server pods at minimum | Requests at minimum (all FoodStack pods) | At full autoscaling | Suggested nodes |
|---|---|---|---|---|
| `monolith` | 2 × core | about 1.6 vCPU, 4 GB | core 6 pods: about 3.6 vCPU, 10 GB | 3 × 4 vCPU / 16 GB |
| `split` | 3 core, 3 order, 2 dispatch, 2 search | about 5 vCPU, 12 GB | about 22 vCPU, 50 GB | 3 to 6 × 8 vCPU / 32 GB |
| `microservices` | 2 per module (3 for order) | about 9 vCPU, 25 GB | well over 60 GB | 6+ × 8 vCPU / 32 GB, with the cluster autoscaler |

Every server pod sets a memory limit equal to its request, so a node never runs out of memory unexpectedly. Kubernetes keeps 2 replicas of each part on different nodes where it can (topology spread), so losing one node never stops the site.

### 3.2 Gateway API

```bash
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.3.0/standard-install.yaml
kubectl get crd gateways.gateway.networking.k8s.io      # must exist
```

Managed clusters sometimes ship these already (GKE with the Gateway API add-on). Use the standard channel, v1.3 or newer.

### 3.3 Istio in ambient mode

Istio gives every FoodStack pod mutual TLS without sidecars (ztunnel on each node), per-request load balancing (a waypoint), and runs the Gateway's Envoy proxies.

```bash
helm repo add istio https://istio-release.storage.googleapis.com/charts && helm repo update

helm install istio-base istio/base -n istio-system --create-namespace --version 1.30.8 --wait

# PILOT_JWT_ENABLE_REMOTE_JWKS=envoy is required: the gateway then fetches FoodStack's token keys
# itself and keeps retrying. Without it istiod fetches them once; if FoodStack is still starting at
# that moment, every signed-in request is refused ("Jwt verification fails") for up to 20 minutes.
helm install istiod istio/istiod -n istio-system --version 1.30.8 --set profile=ambient \
  --set pilot.env.PILOT_JWT_ENABLE_REMOTE_JWKS=envoy --wait

helm install istio-cni istio/cni -n istio-system --version 1.30.8 --set profile=ambient --wait
helm install ztunnel istio/ztunnel -n istio-system --version 1.30.8 --wait

kubectl -n istio-system get pods      # istiod, istio-cni-node-*, ztunnel-* all Running
```

Platform notes:

| Platform | Add to the istiod, istio-cni and ztunnel installs |
|---|---|
| k3s | `--set global.platform=k3s` |
| GKE | `--set global.platform=gke` |
| EKS, AKS, kubeadm | nothing |

Istio 1.30 or newer. If you already run Istio with sidecars, ambient mode can run beside it; FoodStack's pods join ambient through their own label, so the namespace needs no label.

**Without Istio:** set `mesh.enabled=false` and `gateway.className` to your Gateway API implementation (Envoy Gateway, Cilium, NGINX Gateway Fabric, …). You lose the mesh's mutual TLS and per-request balancing (NetworkPolicies still apply), and the edge token check and edge rate limits, which use Istio resources. Turn those off too: `gateway.jwt.enabled=false`, `gateway.rateLimit.enabled=false`. The server still checks every token and keeps its own limits on sign-in, codes, coupons and orders.

### 3.4 cert-manager and a certificate issuer

The chart asks cert-manager for one certificate covering the three host names.

```bash
helm repo add jetstack https://charts.jetstack.io && helm repo update
helm install cert-manager jetstack/cert-manager -n cert-manager --create-namespace \
  --version v1.18.2 --set crds.enabled=true \
  --set config.apiVersion=controller.config.cert-manager.io/v1alpha1 \
  --set config.kind=ControllerConfiguration --set config.enableGatewayAPI=true --wait
```

A Let's Encrypt issuer that answers the challenge through FoodStack's own Gateway (port 80 stays open for this; every other HTTP request is redirected to HTTPS):

```yaml
# letsencrypt.yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: you@example.com                    # expiry warnings
    privateKeySecretRef: { name: letsencrypt-account }
    solvers:
      - http01:
          gatewayHTTPRoute:
            parentRefs: [{ name: foodstack, namespace: foodstack, kind: Gateway }]
```

```bash
kubectl apply -f letsencrypt.yaml
kubectl get clusterissuer letsencrypt        # READY True
```

The certificate is issued once DNS points at the Gateway ([8.3](#83-dns-and-certificates)). Other choices:

- **DNS-01** (wildcard certificates, or a Gateway not reachable from the internet): use your DNS provider's solver in the issuer instead of `http01`.
- **Your own certificate:** create a TLS Secret with all three names in the `foodstack` namespace and set `gateway.tls.secretName` instead of `gateway.tls.certManager.issuer`.

```bash
kubectl -n foodstack create secret tls foodstack-tls --cert=fullchain.pem --key=privkey.pem
```

### 3.5 Metrics Server

Autoscaling reads CPU use from the Metrics Server. Most managed clusters have it (`kubectl top nodes` answers). Otherwise:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.8.0/components.yaml
```

### 3.6 Storage class

The production install keeps no data in the cluster: datastores are outside it. A storage class is needed only for the trial install ([section 4](#4-try-it-first-a-trial-install)) and for the monitoring chart ([section 13](#13-monitoring-logs-and-alerts)). `kubectl get storageclass` should show one marked `(default)`.

---

## 4. Try it first: a trial install

`values-dev.yaml` runs MongoDB, Redis, Kafka, Elasticsearch, a local S3 and Mailpit inside the cluster with small sizes, one replica of everything, plain HTTP on `*.foodstack.localhost`, a test payment page and the public OpenStreetMap servers. **Not for real data**: no backups, no high availability. It still needs [3.2](#32-gateway-api), [3.3](#33-istio-in-ambient-mode) and a storage class.

```bash
helm install foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 \
  -n foodstack --create-namespace --wait --timeout 20m \
  -f https://raw.githubusercontent.com/ilyaslabs/foodstack/v1.0.0/k8s/helm/foodstack/values-dev.yaml

kubectl -n foodstack port-forward svc/foodstack-istio 8080:80
```

Add `--set topology=split` (or `microservices`) to try another shape. The notes printed at the end give the admin password. With the port forward running, set `publicPort: 8080` (`--set publicPort=8080`) so links carry the port, then open `http://admin.foodstack.localhost:8080/admin/`. Emails: `kubectl -n foodstack port-forward svc/foodstack-mailpit 8025:8025`, then `http://localhost:8025`.

Remove it with `helm -n foodstack uninstall foodstack && kubectl delete namespace foodstack` (this also deletes the trial data and keys).

---

## 5. Datastores, buckets and maps

### 5.1 Datastores

Use managed services or operator-run clusters. FoodStack creates its own databases, topics and indexes on start; you only provide empty services and credentials.

| Datastore | Production minimum | Managed examples | What FoodStack uses it for |
|---|---|---|---|
| MongoDB | 7.0+, 3-member replica set (transactions need a replica set) | MongoDB Atlas | All business data, one database per module (`foodstack_identity`, `foodstack_order`, …) |
| Kafka | 3.7+, 3 brokers, KRaft | Confluent Cloud, Amazon MSK, Aiven | Events between modules (outbox), dead letters |
| Redis | 7+, primary with a replica, or Redis Cluster | ElastiCache, Redis Cloud, Memorystore | Cache, locks, work-bucket leases, live tracking, token revocation, rate-limit counters |
| Elasticsearch | 9.x, 3 nodes | Elastic Cloud | Store search (rebuilt from MongoDB when empty) |

Connection details, as they go into the values file:

| Setting | Example | Notes |
|---|---|---|
| `datastores.mongodb.uri` | `mongodb+srv://foodstack:<password>@cluster0.abcde.mongodb.net/?retryWrites=true&w=majority` | No database name in the path: each module picks its own. The user needs `readWriteAnyDatabase` and `dbAdminAnyDatabase` (it creates databases and indexes) |
| `datastores.mongodb.sharding` | `false` | `true` only on a sharded cluster (through `mongos`): the migrations Job then shards each collection on its declared key ([handbook 44](handbook/44-mongodb-sharding.md)) |
| `datastores.redis.url` | `rediss://default:<password>@redis.example.com:6380` | `rediss://` for TLS. For Sentinel or Cluster see [12.3](#123-growing-the-datastores) |
| `datastores.kafka.bootstrap` | `b-1.kafka:9092,b-2.kafka:9092,b-3.kafka:9092` | Plain or TLS listener. For SASL, add Spring's Kafka properties under `server.env` (`SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL`, `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM`, `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG` from a Secret) |
| `datastores.kafka.topicPartitions` | `24` | Partitions per topic, set when a topic is first created. More partitions allow more consumers; adding them later re-maps keys, so choose early |
| `datastores.kafka.replicationFactor` | `3` | Copies of each event; 3 for 3 brokers |
| `datastores.elasticsearch.uris` | `https://foodstack.es.eu-west-1.aws.elastic.cloud:443` | |
| `datastores.elasticsearch.username`, `password` | `elastic`, `<password>` | |
| `datastores.elasticsearch.replicas` | `1` | Copies of the search index; up to the number of nodes minus one |

Network: the pods must reach each service. With a cloud provider's private networking (VPC peering, Private Link, Atlas IP access list with the cluster's egress addresses), keep the datastores off the public internet. Use TLS and passwords on every one.

### 5.2 Buckets

Photos (logos, dishes, rider documents) live in two S3-compatible buckets that you create: `foodstack-public` (anyone can read; photos) and `foodstack-private` (only through FoodStack; documents). Amazon S3, Cloudflare R2 and Backblaze B2 are documented step by step in [handbook 12](handbook/12-storage.md): bucket creation, the public-read policy, the CORS rule for your two sites, and a key limited to the two buckets.

| Setting | Amazon S3 | Cloudflare R2 | Backblaze B2 |
|---|---|---|---|
| `endpoint` | `https://s3.eu-west-1.amazonaws.com` | `https://<account>.r2.cloudflarestorage.com` | `https://s3.eu-central-003.backblazeb2.com` |
| `region` | `eu-west-1` | `auto` | `eu-central-003` |
| `pathStyle` | `false` | `true` | `true` |
| `publicBaseUrl` | `https://foodstack-public.s3.eu-west-1.amazonaws.com` or your CDN | the bucket's public `r2.dev` or custom domain | `https://f003.backblazeb2.com/file/foodstack-public` or your CDN |

On every start the server writes a test file to each bucket, reads the public one without a key, and checks the CORS rule for your two sites. If anything is wrong it stops with `Storage check failed: …` naming the fix. `datastores.s3.publicEndpoint` is only needed when browsers must upload to a different address than the pods use.

### 5.3 Maps

A real install needs a maps provider; the public OpenStreetMap servers are refused, since they allow light use only. Choices and sign-up are in [handbook 13](handbook/13-maps.md).

| Setting | Values | What it does |
|---|---|---|
| `maps.geocoding.provider` | `google` or `nominatim` | Address search for customers and store onboarding |
| `maps.geocoding.url` | your Nominatim-compatible provider's URL | Only with `nominatim` |
| `maps.geocoding.countryCodes` | `pk` | Your country (one deployment per country) |
| `maps.routing.provider` | `none`, `osrm` or `google` | Road distance and driving time for delivery charges, dispatch and ETAs. `none` uses straight-line distance times a road factor |
| `maps.routing.url` | `http://osrm.internal:5000` | Only with `osrm` |
| `maps.tiles.url` | `https://tiles.example.com/{z}/{x}/{y}.png?key=…` | Map tiles in both apps |
| `maps.tiles.attribution` | `© Provider © OpenStreetMap contributors` | The credit your tile provider requires |

`google` needs `GOOGLE_MAPS_API_KEY` in a Secret listed in `server.envFromSecrets` ([7.2](#72-provider-secrets)).

---

## 6. Keys and Secrets

FoodStack's server needs five values that you make **once, before the first install**, and never change. The chart does not make them for you on a real install: it stops with a message naming the missing value and the command that makes it. (Only the trial values of [section 4](#4-try-it-first-a-trial-install) generate throwaway keys.) Because the values come from you, every tool that installs the chart (Helm, Argo CD, Flux, `render.sh`) writes the same Secret, and nothing can replace your keys behind your back.

| Value (chart setting) | Key in the Secret | What it is | Make it with | If it is lost or changed |
|---|---|---|---|---|
| `secrets.piiKey` | `FOODSTACK_PII_KEY` | 32-byte key that encrypts personal data (emails, phone numbers, addresses) before it is stored | `openssl rand -base64 32` | **Personal data can never be read again.** The server refuses to start rather than make a second administrator |
| `secrets.signingKey` | `FOODSTACK_SIGNING_KEYS` | EC P-256 key that signs sign-in tokens | `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 \| openssl pkcs8 -topk8 -nocrypt` | Everyone is signed out once; no data is lost |
| `secrets.internalToken` | `FOODSTACK_INTERNAL_TOKEN` | Password between server groups (Module-Switch) | `openssl rand -hex 24` | Harmless: all pods get the new one at the next rollout |
| `secrets.serviceClientSecret` | `FOODSTACK_SERVICE_CLIENT_SECRET` | Credential for internal service calls | `openssl rand -hex 24` | Same |
| `secrets.adminPassword` | `FOODSTACK_ADMIN_PASSWORD` | First administrator's password, used only when the first administrator is created | at least 12 characters of your choice | Nothing; change the password in the back office |

**Keep a copy of the personal-data key outside the cluster** (password manager, secret manager) before you install. It is the one value nobody can recover.

There are two ways to hand them to the chart.

**A. Your own Secret (recommended).** The keys never appear in a values file or in Git:

```bash
kubectl create namespace foodstack
kubectl -n foodstack create secret generic foodstack-keys \
  --from-literal=FOODSTACK_SIGNING_KEYS="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 \
      | openssl pkcs8 -topk8 -nocrypt | grep -v -- ----- | tr -d '\n')" \
  --from-literal=FOODSTACK_PII_KEY="$(openssl rand -base64 32)" \
  --from-literal=FOODSTACK_INTERNAL_TOKEN="$(openssl rand -hex 24)" \
  --from-literal=FOODSTACK_SERVICE_CLIENT_SECRET="$(openssl rand -hex 24)" \
  --from-literal=FOODSTACK_ADMIN_PASSWORD="$(openssl rand -hex 12)"

kubectl -n foodstack get secret foodstack-keys -o yaml > foodstack-keys.yaml   # your backup: password manager, not Git
```

```yaml
# my-values.yaml
secrets:
  existingSecret: foodstack-keys
```

The same Secret can come from a secret manager instead (External Secrets, Vault, Sealed Secrets), as long as it has these five keys.

**B. In the values file.** Paste each value into its placeholder. Then the values file holds secrets: keep it encrypted (SOPS) or out of Git.

```yaml
secrets:
  signingKey: "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg…"   # the openssl pkcs8 output, PEM lines optional
  piiKey: "q3Jk…="
  internalToken: "6f1c…"
  serviceClientSecret: "a92e…"
  adminPassword: "choose-a-long-one"
```

The chart writes them into the Secret `foodstack-secrets` (a Helm hook, created before the migrations Job and kept by `helm uninstall`).

(Moving from one server: copy `FOODSTACK_SIGNING_KEYS` and `FOODSTACK_PII_KEY` from `/opt/foodstack/.env` instead of generating them.)

Datastore credentials can come from your own Secret too: name it in `datastores.existingSecret`, with the keys `MONGODB_URI`, `REDIS_URL`, `ELASTICSEARCH_PASSWORD`, `S3_ACCESS_KEY` and `S3_SECRET_KEY`. The non-secret addresses (`kafka.bootstrap`, `elasticsearch.uris`, the `s3` addresses) stay in the values file. The rate limiter then needs the Redis address and password separately: `gateway.rateLimit.redis.address` and a Secret with the key `REDIS_AUTH` named in `gateway.rateLimit.redis.existingSecret`.

---

## 7. Write your values file

### 7.1 `my-values.yaml`

A complete production file. Every setting is explained in [section 18](#18-every-chart-setting).

```yaml
# my-values.yaml: keep it in a private repository or password manager; you need it for every upgrade.
topology: monolith                 # monolith | split | microservices | custom (section 11)

hosts:
  web: example.com                 # customer app
  admin: admin.example.com         # back office at /admin/
  api: api.example.com             # REST API; the token issuer

gateway:
  tls:
    certManager: { issuer: letsencrypt }    # or secretName: foodstack-tls
  # Cloud load balancer settings, copied by Istio onto the Service foodstack-istio. Examples:
  # AWS:   service.beta.kubernetes.io/aws-load-balancer-type: external
  #        service.beta.kubernetes.io/aws-load-balancer-nlb-target-type: ip
  #        service.beta.kubernetes.io/aws-load-balancer-scheme: internet-facing
  # Azure: service.beta.kubernetes.io/azure-load-balancer-health-probe-request-path: /healthz/ready
  annotations: {}
  # Proxies that add X-Forwarded-For in front of the gateway (a CDN or an HTTP load balancer: 1).
  # Rate limits are per client IP, so this must be right.
  trustedProxies: 0

secrets:
  adminEmail: you@example.com      # first administrator
  existingSecret: foodstack-keys   # the five keys you made once (section 6)

datastores:
  mongodb:
    uri: "mongodb+srv://foodstack:…@cluster0.abcde.mongodb.net/?retryWrites=true&w=majority"
  redis:
    url: "rediss://default:…@redis.example.com:6380"
  kafka:
    bootstrap: "b-1.kafka:9092,b-2.kafka:9092,b-3.kafka:9092"
  elasticsearch:
    uris: "https://foodstack.es.example.com:443"
    password: "…"
  s3:
    endpoint: https://s3.eu-west-1.amazonaws.com
    region: eu-west-1
    pathStyle: false
    publicBaseUrl: https://foodstack-public.s3.eu-west-1.amazonaws.com
    accessKey: "…"
    secretKey: "…"

maps:
  geocoding: { provider: google, countryCodes: pk }
  routing: { provider: google }
  tiles:
    url: "https://tiles.example.com/{z}/{x}/{y}.png?key=…"
    attribution: "© Example © OpenStreetMap contributors"

server:
  env:
    PAYMENT_GATEWAY: stripe        # none = cash on delivery only
  envFromSecrets: [foodstack-providers]
```

Prefer not to write passwords in the file? Use `datastores.existingSecret` ([section 6](#6-keys-and-secrets)), or keep the file encrypted (SOPS with `helm secrets`).

### 7.2 Provider secrets

Email, payments, push and Google Maps settings go into a Secret that every server pod loads whole. All keys are optional; leave out what you do not use.

```bash
kubectl create namespace foodstack     # if not done yet
kubectl -n foodstack create secret generic foodstack-providers \
  --from-literal=SMTP_HOST=smtp.example.net --from-literal=SMTP_PORT=587 \
  --from-literal=SMTP_USERNAME=… --from-literal=SMTP_PASSWORD=… \
  --from-literal=SMTP_AUTH=true --from-literal=SMTP_STARTTLS=true \
  --from-literal=SMTP_FROM=orders@example.com --from-literal=SMTP_FROM_NAME="Your brand" \
  --from-literal=STRIPE_SECRET_KEY=sk_live_… --from-literal=STRIPE_WEBHOOK_SECRET=whsec_… \
  --from-literal=STRIPE_PUBLISHABLE_KEY=pk_live_… \
  --from-literal=GOOGLE_MAPS_API_KEY=AIza… \
  --from-file=FCM_SERVICE_ACCOUNT=fcm.json \
  --from-literal=FCM_WEB_CONFIG='{"apiKey":"…","projectId":"…","messagingSenderId":"…","appId":"…"}' \
  --from-literal=FCM_VAPID_KEY=BK…
```

| Keys | Purpose | More |
|---|---|---|
| `SMTP_*` | Order emails, sign-in codes, password reset. Without `SMTP_HOST` and `SMTP_FROM` nothing is emailed; the **Email log** page shows each message as "Not sent" | [handbook 15](handbook/15-email.md) |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PUBLISHABLE_KEY` | Card payments with `PAYMENT_GATEWAY: stripe`. Webhook endpoint: `https://api.example.com/api/v1/payment-webhooks/stripe`. Card data stays with Stripe (PCI SAQ A) | [handbook 16](handbook/16-card-payments.md) |
| `GOOGLE_MAPS_API_KEY` | With `maps.geocoding.provider` or `maps.routing.provider` `google` | [handbook 13](handbook/13-maps.md) |
| `FCM_SERVICE_ACCOUNT`, `FCM_WEB_CONFIG`, `FCM_VAPID_KEY` | Push to riders and customers. `FCM_SERVICE_ACCOUNT` is the key file's JSON itself (`--from-file`), not a path | [deployment guide 3.3](deployment-guide.md#33-configure) |

`PAYMENT_GATEWAY: sandbox` (a test page that charges nothing) is refused in production. Other server settings, such as `FOODSTACK_IDENTITY_DELETION_GRACE`, go under `server.env` (plain values) or into this Secret.

### 7.3 Check the file before installing

```bash
helm template foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 \
  -n foodstack -f my-values.yaml > /dev/null && echo "values OK"
```

A missing or wrong setting stops here with a message naming it, for example `datastores.s3.publicBaseUrl: where browsers load the public bucket's files` or `maps.tiles.url: OpenStreetMap's public tiles allow light use only`. With access to the cluster, `helm install … --dry-run=server` also checks every object against the cluster's API.

### 7.4 Your deployment repository

Keep your install's settings in a small Git repository of your own, created once and used for the life of the install. It holds **your settings only**, never a copy of the chart: the chart comes from the registry at the version you name, so upgrading never means merging our changes into yours.

```
foodstack-deploy/
├─ README.md            who runs it, where the cluster is, where the keys are kept
├─ VERSION              1.0.0
├─ my-values.yaml       section 7.1: hosts, datastores, buckets, maps, topology, sizes
├─ values-prod.yaml     optional: only what differs per environment (replicas, hosts)
└─ deploy.sh            the one command below
```

```bash
#!/usr/bin/env bash
# deploy.sh: install or upgrade, the same command every time.
set -euo pipefail
cd "$(dirname "$0")"
helm upgrade --install foodstack oci://ghcr.io/ilyaslabs/charts/foodstack \
  --version "$(cat VERSION)" -n foodstack --create-namespace \
  -f my-values.yaml ${VALUES_EXTRA:+-f "$VALUES_EXTRA"} --wait --timeout 20m
```

How it is used:

| You want to | Change | Then |
|---|---|---|
| Install for the first time | Write `my-values.yaml`; create the Secrets of [section 6](#6-keys-and-secrets) and [7.2](#72-provider-secrets) | `./deploy.sh` |
| Change a setting (more replicas, another topology, a new host) | Edit `my-values.yaml` | Commit, `./deploy.sh` |
| Upgrade FoodStack | Put the new version in `VERSION` after reading its release notes | Commit, `./deploy.sh` ([section 14](#14-upgrades-and-rollback)) |
| Go back | `git revert` the commit | `./deploy.sh`, or `helm -n foodstack rollback foodstack` |
| See what an upgrade would change | | `helm template` both versions and `diff`, or `render.sh` ([section 10](#10-install-one-service-at-a-time-optional)) |

Rules that keep it safe:

- **No secrets in Git.** The key Secret, datastore passwords and provider keys live in Kubernetes Secrets (or a secret manager through External Secrets, Vault or Sealed Secrets) and are only *named* in the values file: `secrets.existingSecret`, `datastores.existingSecret`, `server.envFromSecrets`. If passwords must sit in the values file, encrypt it (SOPS).
- **Never edit the chart.** Everything you need is a setting ([section 18](#18-every-chart-setting)). If something is not, ask for it: a forked chart has to be merged by hand at every release.
- **Every change is a commit**, so the history says who changed what and when, and any earlier state can be restored.
- **Back up the key Secret outside the repository** ([section 6](#6-keys-and-secrets)): the repository can rebuild the install, but not the keys.

**Applying commits automatically.** Instead of running `deploy.sh` by hand, a GitOps tool can watch the repository and apply each commit. With Argo CD, one Application pointing at the chart, with your values from the repository:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: foodstack
  namespace: argocd
spec:
  project: default
  sources:
    - repoURL: ghcr.io/ilyaslabs/charts          # OCI chart registry
      chart: foodstack
      targetRevision: 1.0.0                      # the version; change it to upgrade
      helm:
        releaseName: foodstack
        valueFiles: [$values/my-values.yaml]
    - repoURL: https://github.com/<you>/foodstack-deploy.git
      targetRevision: main
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: foodstack
  syncPolicy:
    syncOptions: [CreateNamespace=true]
```

Argo CD renders the chart itself rather than through `helm install`, so the chart's `lookup` of existing keys does not run: always use `secrets.existingSecret` with Argo CD, or each sync would generate new keys. Flux's HelmRelease runs Helm itself and has no such limit.

If you prefer to see every Kubernetes object in Git, commit the folders `render.sh` writes ([section 10](#10-install-one-service-at-a-time-optional)), without `00-secrets`, and render again on every change.

---

## 8. Install

### 8.1 Run the install

```bash
helm install foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 \
  -n foodstack --create-namespace -f my-values.yaml --wait --timeout 20m
```

What happens, in order:

1. The Secrets `foodstack-secrets` (from your `secrets.*` values) and `foodstack-datastores` are created, unless you named your own.
2. The Job `foodstack-migrations` runs every module's database changes. It needs MongoDB; a wrong address fails here. Follow it with `kubectl -n foodstack logs -f job/foodstack-migrations`.
3. The Deployments, Services, Gateways, routes, mesh and network policies are created. Istio starts the gateway pods and the waypoint.
4. The server pods start. On start each one checks the buckets, creates Kafka topics and Elasticsearch indexes, and the first one creates the first administrator. A pod is ready when `/actuator/health/readiness` answers.
5. Helm prints the notes: addresses, the admin password command, the Gateway address command.

The first install takes 5 to 10 minutes. Watch it from another terminal:

```bash
kubectl -n foodstack get pods -w
```

### 8.2 The administrator's password

It is the `adminPassword` you chose in [section 6](#6-keys-and-secrets). To read it back:

```bash
kubectl -n foodstack get secret foodstack-keys -o jsonpath='{.data.FOODSTACK_ADMIN_PASSWORD}' | base64 -d; echo   # or foodstack-secrets
```

### 8.3 DNS and certificates

```bash
kubectl -n foodstack get gateway foodstack -o jsonpath='{.status.addresses[0].value}'; echo
```

- An IP address: create `A` records for the three names pointing at it.
- A host name (AWS load balancers): create `CNAME` records (an `ALIAS` at the apex, such as `example.com`).

Then the certificate:

```bash
kubectl -n foodstack get certificate foodstack          # READY True within a few minutes of DNS
kubectl -n foodstack describe certificate foodstack     # if not: the events say why
```

Before switching real traffic you can test with your hosts file (`<address> example.com admin.example.com api.example.com`), accepting the browser warning until the certificate exists.

### 8.4 First sign-in

Open `https://admin.example.com/admin/` and sign in with `secrets.adminEmail` and the password from 8.2. The back office asks you to set up an authenticator app (two-step sign-in is required for ADMIN and FINANCE accounts). Then change the password (account menu) and follow the first-launch guides: [handbook 17 to 19](handbook/README.md#start-here-your-first-launch) (branding, languages, ordering rules, cities, delivery pricing, first store, a test order).

---

## 9. Check it works

| Check | Command or place | Expected |
|---|---|---|
| Pods | `kubectl -n foodstack get pods` | All `Running`, `READY 1/1`; the migrations Job is gone (deleted after success) |
| Gateway | `kubectl -n foodstack get gateway` | `foodstack` and `foodstack-internal` `PROGRAMMED True` |
| Routes | `kubectl -n foodstack get httproute` | Each with `Accepted` in `kubectl describe` |
| Certificate | `kubectl -n foodstack get certificate` | `READY True` |
| API | `curl -s https://api.example.com/api/v1/settings/locale` | JSON with your language and currency |
| Token keys | `curl -s https://api.example.com/oauth2/jwks` | One EC key |
| Server health | `kubectl -n foodstack port-forward deploy/foodstack-core 8082:8082`, then `curl -s localhost:8082/actuator/health` | `{"status":"UP"…}` |
| Storage | `kubectl -n foodstack logs deploy/foodstack-core \| grep -i storage` | No `Storage check failed` |
| Autoscalers | `kubectl -n foodstack get hpa` | Current CPU shown (not `<unknown>`) |
| Email | Sign in once with **Email me a sign-in code**, then **Email log** in the back office | The message shows as Sent |
| End to end | A test order, accepted, delivered, then cancelled with a reason ([handbook 19](handbook/19-first-store.md)) | Customer, store and rider see each step live |

Load test before opening to customers, from an address the rate limits allow. It only reads (search, store pages, menus, delivery quotes), with 50 users at the peak, and fails if more than 1 % of requests fail or a step's 95th percentile passes 300 ms:

```bash
docker run --rm -i -e BASE_URL=https://api.example.com -e LAT=31.52 -e LNG=74.34 \
  grafana/k6:1.8.1 run - < tools/k6/browse.js
```

---

## 10. Install one service at a time (optional)

`helm install` starts every service together. To review the objects first and start them yourself, one service at a time in the order you choose, write them into one folder per service with `render.sh`, which comes inside the chart, and apply each folder with `kubectl`.

```bash
helm pull oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 --untar
bash foodstack/render.sh --out rendered -n foodstack -f my-values.yaml --set topology=split
```

```
rendered/
├─ 00-secrets      signing key, personal-data key, admin password, datastore addresses: do not commit
├─ 01-datastores   trial datastores (values-dev.yaml only)
├─ 02-shared       gateway, routes, rate limits, mesh and network policies
├─ 03-migrations   the database migrations Job
├─ server-core     Deployment, Service, ConfigMap, ServiceAccount, autoscaler and budget of one service
├─ server-order    (one folder per server Deployment of the topology)
├─ ...
├─ web
└─ backoffice
```

Every option other than `--out`, `--release` and `--offline` goes to `helm template` unchanged. Nothing is applied until you apply it. Prerequisites first, then services in any order:

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

- Start the service that runs `identity` first (`server-core` in monolith and split, `server-identity` in microservices): every service checks sign-in tokens against it. A service whose neighbours are not running yet starts, and its calls to them fail until they are; their routes answer 503 meanwhile.
- `00-secrets` holds the keys from your `secrets.*` values, the same on every render. With `secrets.existingSecret` ([section 6](#6-keys-and-secrets)) there is no key file at all, which is the better choice when the folders go into Git. (Only trial values generate keys; for them `render.sh` reads the Secret already in the cluster so a second render keeps it, and `--offline` makes new ones.)
- To upgrade, render again with the new chart version, run the migrations Job again, then apply the service folders in your order. A folder not yet applied keeps running the old version, so apply them all before you leave the install: the services are tested together at one version only.
- An install is managed either by Helm or by `kubectl` from these folders, never both: Helm refuses objects it did not create, and `helm upgrade` would undo changes made with `kubectl`. You can still render the folders to review a Helm upgrade before running it.
- GitOps tools (Argo CD, Flux) can point at the Helm chart directly, or at the rendered folders kept in Git without `00-secrets`.

---

## 11. Choose and change the topology

One setting decides how the 15 modules are grouped into Deployments. The data does not depend on it: every topology uses the same databases, so you can change it at any time with `helm upgrade`.

| Topology | Deployments | When |
|---|---|---|
| `monolith` | `core` runs every module | Start here. Scale by adding replicas |
| `split` | `core` (12 modules), `order`, `dispatch`, `search` | Ordering at lunch time or search needs far more than the rest |
| `microservices` | One per module (15) | Large teams or very uneven load; costs more memory (each pod carries the JVM) |
| `custom` | Your own groups in `server.deployments` | Any other grouping |

Example sizes for the presets come inside the chart:

```bash
helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 -n foodstack \
  -f my-values.yaml -f https://raw.githubusercontent.com/ilyaslabs/foodstack/v1.0.0/k8s/helm/foodstack/values-split.yaml
```

A custom grouping (every module exactly once; the chart refuses an unknown module, a module listed twice, or a module another one needs that runs nowhere):

```yaml
topology: custom
server:
  deployments:
    core:     { modules: [identity, settings, geo, media, store, cart, promotion, payment, review, notification, ledger] }
    order:    { modules: [order], replicas: 3, autoscaling: { min: 3, max: 20 } }
    delivery: { modules: [rider, dispatch], replicas: 2, autoscaling: { min: 2, max: 10 } }
    search:   { modules: [search], replicas: 2 }
```

A topology change rolls one Deployment at a time; calls between modules are retried while their target starts, so traffic keeps flowing. The routes follow automatically. Step by step: [handbook 42](handbook/42-split-modules.md).

---

## 12. Scaling

### 12.1 More server pods

Every Deployment has a HorizontalPodAutoscaler on CPU (default: 2 to 6 pods at 70 %). Change it for all groups under `server.defaults`, or for one under `server.overrides.<name>`:

```yaml
server:
  overrides:
    order:
      autoscaling: { enabled: true, min: 3, max: 20, targetCPU: 70 }
      pdb: { minAvailable: 2 }
      resources:
        requests: { cpu: 500m, memory: 1Gi }
        limits: { memory: 1Gi }
```

`helm upgrade` changes only the Deployments whose settings changed. Background work needs no leader: the outbox relay, the dispatch matcher and the timeout sweeps split 64 work buckets between the pods, so a new pod takes a share within seconds and a lost pod's share moves to the others. Step by step: [handbook 41](handbook/41-add-server-replicas.md).

**Kafka consumers:** a topic with 24 partitions feeds at most 24 consumers per group. The chart lowers each module's listener threads (`kafkaConcurrency`, default 3) so that `max pods × threads ≤ topicPartitions`, and the install notes show the result.

### 12.2 The apps and the gateway

`web.autoscaling` (2 to 6), `backoffice.autoscaling` (off; it is static files), and `gateway.autoscaling` (2 to 10 Envoy pods for each of the two Gateways) work the same way. The rate limiter's `gateway.rateLimit.replicas` is fixed (2).

### 12.3 Growing the datastores

| Store | How to grow | FoodStack setting |
|---|---|---|
| MongoDB, more copies | Add replica set members | None |
| MongoDB, sharding | Convert to a sharded cluster, point the URI at `mongos` | `datastores.mongodb.sharding: true`; the next upgrade's migrations shard each collection on its key, also with data in it ([handbook 44](handbook/44-mongodb-sharding.md)) |
| MongoDB, one module apart | Copy `foodstack_<module>` to a new cluster | A Secret with key `MONGODB_URI`, then `server.moduleDatastores.<module>.mongodbUriSecret`; only that module's Deployment restarts ([handbook 45](handbook/45-module-database.md)) |
| Redis | Replicas with Sentinel, or Redis Cluster | Under `server.env`: `SPRING_DATA_REDIS_SENTINEL_MASTER` + `SPRING_DATA_REDIS_SENTINEL_NODES`, or `SPRING_DATA_REDIS_CLUSTER_NODES`, with `SPRING_DATA_REDIS_PASSWORD`; and `gateway.rateLimit.redis.type`/`address` for the rate limiter ([handbook 46](handbook/46-redis.md)) |
| Kafka | Add brokers | `datastores.kafka.replicationFactor: 3` ([handbook 47](handbook/47-kafka.md)) |
| Elasticsearch | Add nodes | `datastores.elasticsearch.replicas`; more primary shards take effect on the next index rebuild ([handbook 48](handbook/48-elasticsearch.md)) |
| Images and pages | A CDN in front of the bucket and the API | `datastores.s3.publicBaseUrl` to the CDN; store pages answer with `Cache-Control: public, s-maxage=30` ([handbook 49](handbook/49-cdn.md)) |

When to take each step: [handbook 30](handbook/30-when-to-grow.md) and [architecture 8](architecture/08-scaling.md).

---

## 13. Monitoring, logs and alerts

```bash
helm install foodstack-observability oci://ghcr.io/ilyaslabs/charts/foodstack-observability \
  --version 1.0.0 -n foodstack-observability --create-namespace --wait
helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0 -n foodstack \
  -f my-values.yaml --set server.tracing.enabled=true
```

Install it in the namespace `foodstack-observability` with that release name, so FoodStack's default tracing address finds the collector. It runs one small pod each of Prometheus, Grafana, Loki and Tempo (Alertmanager with `alertmanager.enabled=true`), and an OpenTelemetry Collector on every node that ships the FoodStack pods' logs to Loki and receives their traces. Prometheus joins the Istio mesh to read the server's metrics (`ambient: true`; set `false` without Istio).

**Grafana** is not on the internet:

```bash
kubectl -n foodstack-observability port-forward svc/foodstack-grafana 3000:3000
kubectl -n foodstack-observability get secret foodstack-grafana -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

Open `http://localhost:3000` as `admin`. The **FoodStack** folder has three dashboards: overview (traffic, errors and latency per Deployment, heap, database and Redis time), orders and delivery (the order funnel, time to find a rider, pickup to delivery), and events (outbox backlog and age, consumer lag, failures, failed events). Logs are under Explore → Loki; a log line's trace id opens the trace in Tempo.

**Alerts** (thresholds under `prometheus.alerts`):

| Alert | Fires when |
|---|---|
| FoodStackPodDown | A server pod has not answered for 5 minutes |
| FoodStackErrors | More than 2 % of a Deployment's requests end in a server error for 10 minutes |
| FoodStackSlow | API requests take over 1 s at the 95th percentile for 10 minutes |
| FoodStackOutboxStuck | A module's oldest unpublished event is over 2 minutes old |
| FoodStackDeadLetters | An event failed for good in the last 15 minutes (replay it on the back office's **Failed events** page) |
| FoodStackConsumerLag | A consumer group is over 10,000 events behind for 10 minutes |
| FoodStackMemory | A pod's heap is over 90 % full after garbage collection for 10 minutes |
| CertificateExpiring | A cert-manager certificate expires within 14 days |

To receive them by email or chat, set `alertmanager.enabled=true` and `alertmanager.config` (route and receivers; an email example is in the chart's values). Retention: `prometheus.retention` 15 days, `loki.retention` 7 days, `tempo.retention` 3 days, each on its own volume (`*.storage`).

**Your own monitoring instead:** scrape pods annotated `prometheus.io/scrape` (port 8082, `/actuator/prometheus`; from inside the mesh), read the pods' JSON logs (`server.logFormat: ecs`, with the trace id), and set `server.tracing.endpoint` to your OTLP/HTTP collector. `server.tracing.sampling` keeps 10 % of traces by default.

More: [handbook 23](handbook/23-health-and-dashboards.md).

---

## 14. Upgrades and rollback

```bash
# Read the release notes first: https://github.com/ilyaslabs/foodstack/releases

helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.1.0 \
  -n foodstack -f my-values.yaml --wait --timeout 20m
```

- The migrations Job runs first. Database changes are always compatible with the previous release, so old pods keep working while new ones roll in, one pod at a time (PodDisruptionBudgets keep at least one serving), with no downtime.
- Always pass the same `-f my-values.yaml`. `--reuse-values` keeps old values but misses new defaults; prefer the file.
- Upgrade one version at a time when the release notes say so.

Roll back:

```bash
helm -n foodstack history foodstack
helm -n foodstack rollback foodstack            # to the previous revision
```

A rollback is safe because the previous release understands the database as the new one left it. Step by step: [handbook 21](handbook/21-upgrades.md).

---

## 15. Backups and disaster recovery

| What | How | Without it |
|---|---|---|
| The five keys (`foodstack-keys`, or `secrets.*` in your values) | A copy outside the cluster from the day you make them ([section 6](#6-keys-and-secrets)) | **Personal data unreadable** |
| `my-values.yaml` and `foodstack-providers` | Password manager or private repository | Reinstalling means rewriting them |
| MongoDB | The provider's continuous backup with point-in-time restore, or `mongodump` of each `foodstack_*` database on a schedule | Data loss |
| Buckets | Versioning, and replication to another region | Lost photos and documents |
| Kafka, Redis, Elasticsearch | Nothing: rebuilt from MongoDB (events already applied, caches, search index) | Nothing lasting |

Target for the reference setup: restore point under 15 minutes old, service back within 1 hour. Rehearse a restore every quarter: a new namespace or cluster, the same key Secret, the restored database, `helm install` of the same version. Step by step: [handbook 20](handbook/20-backups.md#kubernetes).

---

## 16. Security checklist

- [ ] Istio ambient with `mesh.mtls: STRICT` (default).
- [ ] istiod runs with `PILOT_JWT_ENABLE_REMOTE_JWKS=envoy` ([3.3](#33-istio-in-ambient-mode)).
- [ ] Gateway token check on: `gateway.jwt.enabled: true` (default).
- [ ] Edge rate limits on (`gateway.rateLimit.enabled: true`, default), `gateway.rateLimit.limits` reviewed for your traffic, and `gateway.trustedProxies` matching what is in front of the gateway.
- [ ] NetworkPolicies on: `networkPolicies.enabled: true` (default).
- [ ] The five keys kept outside the cluster, above all the personal-data key.
- [ ] Datastores reachable only from the cluster, with TLS and passwords.
- [ ] No passwords in committed values files (`existingSecret`, SOPS, or a secret manager).
- [ ] HTTPS only (`scheme: https`, default); HTTP redirects to HTTPS.
- [ ] Two-step sign-in for every back-office account that can change money or settings (required for ADMIN and FINANCE).
- [ ] Image signatures checked by your admission controller (cosign), if you run one.
- [ ] Kubernetes RBAC: only operators can read Secrets in `foodstack`.

---

## 17. Uninstall

```bash
helm -n foodstack uninstall foodstack
```

This removes FoodStack's pods and routes. It **keeps** the Secret `foodstack-secrets` (and `foodstack-datastores`), on purpose, and touches none of your data in MongoDB, Kafka, Redis, Elasticsearch or the buckets. A later `helm install` into the same namespace uses the same keys and finds all data.

To remove everything in the cluster, including the keys (after backing them up if the data stays): `kubectl delete namespace foodstack`. The data outside the cluster is deleted only by you, in each service.

---

## 18. Every chart setting

Defaults in brackets. The chart's own `values.yaml` has the same list with comments (`helm show values oci://ghcr.io/ilyaslabs/charts/foodstack --version 1.0.0`).

### General

| Setting | Default | Meaning |
|---|---|---|
| `topology` | `monolith` | `monolith`, `split`, `microservices` or `custom` ([section 11](#11-choose-and-change-the-topology)) |
| `hosts.web`, `hosts.admin`, `hosts.api` | `example.com`, `admin.example.com`, `api.example.com` | The three public names; may be the same name. Every name serves every route |
| `scheme` | `https` | `http` only for a trial without certificates |
| `publicPort` | empty | Port in public addresses when not 80/443 (port forwards) |
| `image.registry`, `image.repository` | `docker.io`, `ilyasdotdev/ilyaslabs-foodstack` | Where the images are; `ghcr.io` and `ilyaslabs/foodstack` for GitHub's copy, or your own mirror |
| `image.tag` | the chart's version | Use the chart version that matches; set only for a mirror or a snapshot |
| `image.pullPolicy` | `IfNotPresent` | |
| `image.pullSecrets` | `[]` | For a private mirror: `[{ name: regcred }]` |

### Server

| Setting | Default | Meaning |
|---|---|---|
| `server.defaults.replicas` | `2` | Pods per group when autoscaling is off |
| `server.defaults.autoscaling` | `enabled: true, min: 2, max: 6, targetCPU: 70` | |
| `server.defaults.pdb.minAvailable` | `1` | Pods kept during node maintenance; `pdb: null` turns the budget off |
| `server.defaults.resources` | `requests: 500m / 1536Mi, limits: 1536Mi` | |
| `server.defaults.kafkaConcurrency` | `3` | Listener threads per module, lowered automatically ([12.1](#121-more-server-pods)) |
| `server.defaults.env`, `nodeSelector`, `tolerations`, `affinity` | empty | Per-pod extras |
| `server.overrides.<group>` | `{}` | Any of the `defaults` settings for one group (`core`, `order`, …) |
| `server.deployments` | `{}` | Only with `topology: custom`: group name → `{ modules: [...] }` plus any `defaults` setting |
| `server.moduleDatastores.<module>.mongodbUriSecret` | `{}` | A module's own MongoDB (Secret key `MONGODB_URI`) |
| `server.env` | `PAYMENT_GATEWAY: none` | Extra plain settings for every server pod |
| `server.envFromSecrets` | `[]` | Secrets loaded whole into every server pod ([7.2](#72-provider-secrets)) |
| `server.maxRamPercentage` | `75` | JVM heap as a share of the memory limit |
| `server.logFormat` | `ecs` | `ecs` or `logstash` JSON lines with the trace id, or empty for plain text |
| `server.tracing.enabled`, `endpoint`, `sampling` | `false`, the observability collector, `0.1` | OpenTelemetry traces |

### Migrations, web, back office

| Setting | Default | Meaning |
|---|---|---|
| `migrations.enabled` | `true` | The database Job before install and upgrade |
| `migrations.activeDeadlineSeconds` | `900` | Time limit; raise for a large first sharding |
| `migrations.resources` | `500m / 1Gi` | |
| `web.replicas`, `web.autoscaling`, `web.pdb`, `web.resources`, `web.env` | `2`, `2–6 at 70 %`, `1`, `200m / 256Mi (limit 512Mi)`, `{}` | Customer app |
| `backoffice.replicas`, `backoffice.autoscaling`, `backoffice.pdb`, `backoffice.resources`, `backoffice.env` | `2`, off, `1`, `50m / 64Mi (limit 128Mi)`, `{}` | Back office |

### Secrets, datastores, maps

| Setting | Default | Meaning |
|---|---|---|
| `secrets.existingSecret` | empty | Your own Secret with the five keys ([section 6](#6-keys-and-secrets)); the placeholders below are then ignored |
| `secrets.signingKey`, `piiKey`, `internalToken`, `serviceClientSecret`, `adminPassword` | empty, required | The five keys, made once ([section 6](#6-keys-and-secrets)). Only trial values generate them |
| `secrets.adminEmail` | `admin@example.com` | First administrator |
| `datastores.existingSecret` | empty | Your own datastore Secret |
| `datastores.mongodb.uri`, `sharding` | empty, `false` | [5.1](#51-datastores) |
| `datastores.redis.url` | empty | |
| `datastores.kafka.bootstrap`, `topicPartitions`, `replicationFactor` | empty, `24`, `3` | |
| `datastores.elasticsearch.uris`, `username`, `password`, `replicas` | empty, `elastic`, empty, `1` | |
| `datastores.s3.endpoint`, `region`, `pathStyle`, `publicEndpoint`, `publicBaseUrl`, `accessKey`, `secretKey`, `publicBucket`, `privateBucket`, `createBuckets` | …, `true`, …, `foodstack-public`, `foodstack-private`, `false` | [5.2](#52-buckets) |
| `maps.geocoding.*`, `maps.routing.*`, `maps.tiles.*` | | [5.3](#53-maps) |
| `maps.allowPublicOpenStreetMap` | `false` | `true` only on your own computer (the playground) |

### Gateway, mesh, network

| Setting | Default | Meaning |
|---|---|---|
| `gateway.enabled` | `true` | Create the Gateways and routes |
| `gateway.className` | `istio` | Your Gateway API implementation |
| `gateway.annotations` | `{}` | Copied to the load balancer Service ([7.1](#71-my-valuesyaml)) |
| `gateway.tls.secretName` | empty | Your own TLS Secret |
| `gateway.tls.certManager.issuer` | empty | A ClusterIssuer name ([3.4](#34-cert-manager-and-a-certificate-issuer)) |
| `gateway.trustedProxies` | `0` | Proxies in front that add X-Forwarded-For |
| `gateway.autoscaling` | `min: 2, max: 10, targetCPU: 70` | Envoy pods of each Gateway |
| `gateway.jwt.enabled`, `jwksUri` | `true`, the internal gateway's `/oauth2/jwks` | Edge token check |
| `gateway.rateLimit.enabled`, `image`, `replicas` | `true`, `envoyproxy/ratelimit`, `2` | Edge rate limits |
| `gateway.rateLimit.redis.address`, `type`, `tls`, `existingSecret` | from `datastores.redis.url`, `single`, `false`, empty | The counters' Redis |
| `gateway.rateLimit.limits.all`, `api`, `signIn`, `orders` | `1200`, `600`, `30`, `30` per minute per IP | Every request, `/api/*`, sign-in paths, placing orders |
| `gateway.rateLimit.retryAfterSeconds` | `60` | `Retry-After` on a 429 |
| `mesh.enabled`, `mtls`, `waypoint.enabled` | `true`, `STRICT`, `true` | Istio ambient policies |
| `networkPolicies.enabled` | `true` | |
| `dev.*` | off | Trial datastores (`values-dev.yaml`) |

---

## 19. Troubleshooting

Start with `kubectl -n foodstack get pods`, then `kubectl -n foodstack describe pod <pod>` (events) and `kubectl -n foodstack logs <pod>` (the reason, in plain words).

| What you see | Why, and what to do |
|---|---|
| `helm install` stops with a message about a setting | A required value is missing or refused; the message names it ([7.3](#73-check-the-file-before-installing)). `secrets.signingKey` and the other keys: make them as in [section 6](#6-keys-and-secrets) |
| `helm install` times out in the migrations Job | `kubectl -n foodstack logs job/foodstack-migrations`: usually the MongoDB address, password or network access. Fix and run the install again |
| Pods `Pending` | Not enough CPU or memory on the nodes (`describe` says `Insufficient`): add nodes or lower `resources` |
| `ImagePullBackOff` | The version does not exist or the mirror needs `image.pullSecrets` |
| `CreateContainerConfigError` | A Secret named in the values is missing or in another namespace (`foodstack-providers`, `existingSecret`) |
| Pods restart with `Storage check failed: …` | A bucket setting is wrong; the message says which ([handbook 12](handbook/12-storage.md#if-something-goes-wrong)) |
| Pods restart with `FOODSTACK_PII_KEY does not open the accounts already stored` | The key Secret does not hold the original personal-data key. Restore it from your backup |
| Every signed-in request answers 401 `Jwt verification fails` | istiod lacks `PILOT_JWT_ENABLE_REMOTE_JWKS=envoy` ([3.3](#33-istio-in-ambient-mode)); add it and restart istiod |
| 503 `no healthy upstream` on some paths | That path's Deployment has no ready pod: `kubectl -n foodstack get deploy` |
| 503 `remote connection failure` after a node reboot | Istio's ztunnel came back after the pods: `kubectl -n foodstack rollout restart deployment` |
| 429 for everyone | Rate limits count the load balancer's address, not the client's: set `gateway.trustedProxies` |
| Certificate never `READY` | `kubectl -n foodstack describe certificate foodstack` and `kubectl -n foodstack get challenges`: DNS not yet pointing at the Gateway, or port 80 blocked |
| Gateway address stays empty | The cloud load balancer was not created: `kubectl -n foodstack describe svc foodstack-istio`; check `gateway.annotations` for your cloud |
| Autoscaler shows `<unknown>` | Metrics Server missing ([3.5](#35-metrics-server)) |
| Search finds no stores | Elasticsearch unreachable: `kubectl -n foodstack logs deploy/foodstack-core \| grep -i search` (in split, `deploy/foodstack-search`). The index refills itself once it is reachable |
| Emails not sent | **Email log** in the back office shows why; check the `SMTP_*` keys in `foodstack-providers`, then `kubectl -n foodstack rollout restart deployment` |
| Prometheus shows FoodStack targets down | Prometheus is not in the mesh: the observability chart's `ambient: true` on an Istio cluster |
| Events pile up (FoodStackOutboxStuck) | Kafka unreachable or full; the backlog is sent once it is back, nothing is lost |
| A failed event (FoodStackDeadLetters) | Back office **Failed events**: see why, fix the cause, replay |

More symptoms and fixes: [handbook troubleshooting](handbook/90-troubleshooting.md).
