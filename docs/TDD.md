# FoodStack: Technical Design Document (TDD)

| | |
|---|---|
| Status | Draft v1, agreed in grooming with ilyas on 2026-10-27 |
| Owner | ilyas (decisions), Claude (architecture draft) |
| Related | [BRD](BRD.md), [PRD](PRD.md), [Module-Switch](https://github.com/ilyaslabs/module-switch), [Module-Switch example](https://github.com/ilyaslabs/module-switch-example) |

## 1. Goals and principles

1. **Modular monolith first, services by configuration.** Every backend module follows Module-Switch rules, so it can run `local`, `http` or `off` without code changes.
2. **Each module owns its data.** Its MongoDB collections, Redis keys, Elasticsearch indices, Kafka topic and Liquibase changelog all carry the module name as a prefix. Extracting a module means pointing it at its own datastore, not untangling shared tables.
3. **Synchronous calls for queries and validation, Kafka events for consequences.** A module calls another module's `-api` when it needs an answer now; it publishes an event when something happened that others may react to.
4. **Pure REST.** Web apps and future native apps use the same documented API.
5. **Secure by default.** mTLS everywhere, least privilege, no secrets in images, PII encrypted.
6. **Boring, portable infrastructure.** Any conformant Kubernetes, no cloud-specific services in the default chart.

## 2. Decision record (from grooming)

| # | Topic | Decision |
|---|---|---|
| D1 | Deployment model | One operator per deployment (white-label), not multi-tenant |
| D2 | Language and framework | Java 25, Spring Boot 4, Module-Switch |
| D3 | Build | Maven multi-module, `${revision}` versioning, published to Maven Central via `ilyaslabs/cicd` reusable workflows |
| D4 | Persistence | MongoDB, collections prefixed `<module>_` |
| D5 | Migrations | Liquibase 5 with `liquibase-mongodb`, one changelog per module (Liquibase 5 is FSL licensed: free to use, only competing Liquibase products are restricted) |
| D6 | Cache | Redis, keys prefixed `foodstack:<module>:` |
| D7 | Search | Elasticsearch 9 behind a `search` module SPI, so an OpenSearch adapter remains possible |
| D8 | Events | **Kafka from day one**, with a transactional outbox per module |
| D9 | Identity | Own `identity` module on Spring Authorization Server (OIDC, JWT): phone OTP, email and password, social login |
| D10 | Real time | Server-Sent Events for web, FCM push for devices |
| D11 | Maps | Provider SPI; default OpenStreetMap stack (MapLibre, Nominatim, OSRM); Google Maps adapter |
| D12 | Front-end | Angular workspace, two apps (`web`, `backoffice`), Angular Material 3 with design tokens, no micro-frontends |
| D13 | Repository | Monorepo: backend, frontend, `k8s/helm`, docs |
| D14 | Artifacts | Maven `io.github.ilyaslabs:foodstack-*`; images `ilyaslabs/foodstack-server`, `ilyaslabs/foodstack-web`, `ilyaslabs/foodstack-backoffice` on Docker Hub, mirrored to GHCR |
| D15 | Kubernetes | Helm chart; managed datastores in production, in-cluster datastores with `values-dev.yaml`; monolith by default, `values-split.yaml` preset |
| D16 | Service mesh | Istio ambient mode (ztunnel mTLS, waypoints for L7), Gateway API for ingress |
| D17 | Observability | OpenTelemetry; optional Prometheus, Grafana, Tempo and Loki stack |

## 3. System context

```
                         ┌────────────────────────── Kubernetes cluster ───────────────────────────┐
 Customer browser ──┐    │                                                                          │
 Store tablet ──────┤    │  Gateway API (Istio) ──► foodstack-web (nginx, Angular SSR)               │
 Rider phone (PWA) ─┼────┼─►  TLS, cert-manager  ──► foodstack-backoffice (nginx, Angular)          │
 Admin desktop ─────┤    │                      └─► foodstack-server (Spring Boot, all modules)      │
 Future mobile app ─┘    │                              │  ▲ module-switch http (internal :8081)     │
                         │                              ▼  │   when split into several Deployments   │
                         │   MongoDB   Redis   Kafka   Elasticsearch   (managed in prod, in-cluster dev)
                         └──────────────────────────────────────────────────────────────────────────┘
 External: payment gateway, SMTP, SMS provider, FCM, map tiles / geocoding / routing
```

## 4. Repository layout

```
foodstack/
├─ pom.xml                         parent: spring-boot-starter-parent 4.x, ${revision}, module-switch BOM
├─ foodstack-bom/                  BOM of all foodstack artifacts
├─ platform/                       shared, non-module libraries (no business logic)
│  ├─ platform-core/               money, ids, time, problem+json, pagination, idempotency, i18n text type
│  ├─ platform-mongo/              per-module MongoTemplate factory, Liquibase runner, outbox, inbox
│  ├─ platform-kafka/              event envelope, publisher, consumer support, DLT
│  ├─ platform-security/           resource-server config, permission model, PII crypto
│  └─ platform-test/               Testcontainers fixtures, ModuleRules test, contract test helpers
├─ modules/
│  ├─ identity/   identity-api/   identity-impl/
│  ├─ settings/   settings-api/   settings-impl/
│  ├─ geo/        geo-api/        geo-impl/        geo-provider-osm/   geo-provider-google/
│  ├─ store/      store-api/      store-impl/
│  ├─ search/     search-api/     search-impl/     (Elasticsearch adapter inside impl)
│  ├─ cart/       cart-api/       cart-impl/
│  ├─ promotion/  promotion-api/  promotion-impl/
│  ├─ payment/    payment-api/    payment-impl/    payment-gateway-card/  (first card adapter)
│  ├─ order/      order-api/      order-impl/
│  ├─ dispatch/   dispatch-api/   dispatch-impl/
│  ├─ rider/      rider-api/      rider-impl/
│  ├─ review/     review-api/     review-impl/
│  ├─ notification/ notification-api/ notification-impl/ (email, SMS, FCM channel adapters)
│  └─ ledger/     ledger-api/     ledger-impl/
├─ app/
│  └─ foodstack-server/            @SpringBootApplication, depends on every *-impl; application*.yaml; Dockerfile
├─ frontend/                       Angular workspace (section 13)
├─ k8s/
│  └─ helm/
│     ├─ foodstack/                main chart (section 15)
│     └─ foodstack-observability/  optional monitoring chart
├─ docs/                           BRD, PRD, TDD, ADRs, guides
├─ tools/                          load tests (k6), seed data, scripts
└─ .github/workflows/              CI calling ilyaslabs/cicd reusable workflows
```

Java packages: `io.github.ilyaslabs.foodstack.<module>.api` and `io.github.ilyaslabs.foodstack.<module>.impl`. Maven artifacts: `foodstack-<module>-api`, `foodstack-<module>-impl`.

## 5. Backend module design

### 5.1 Anatomy of a module

```
order-api/                          @ModuleApi("order")  (package-info.java)
  OrderApi.java                     interface; every param @Param; no overloads
  model/  OrderView, OrderLine…     records (Jackson-serializable)
  events/ OrderEvent (sealed) + OrderPlaced, OrderAccepted…   records, published to Kafka
order-impl/                         @Module("order")     (package-info.java)
  OrderModuleConfiguration.java     @EnableMongoRepositories(mongoTemplateRef = "orderMongoTemplate"),
                                    MongoTemplate bean, Liquibase runner, Kafka listeners, caches
  domain/                           aggregates, state machine, domain services
  persistence/                      @Document("order_orders") entities, repositories
  web/                              REST controllers under /api/v1/orders…
  messaging/                        outbox publisher, event consumers
  OrderService.java                 implements OrderApi
  src/main/resources/db/changelog/order/db.changelog-master.yaml
```

Rules (enforced by Module-Switch at startup and by `ModuleRules.verify("io.github.ilyaslabs.foodstack")` in CI):

- A module depends only on other modules' `-api` artifacts and on `platform-*`.
- Errors cross module boundaries only as `ModuleException` subtypes with stable `code` values (for example `STORE_CLOSED`, `COUPON_EXPIRED`).
- All Spring Data, Kafka listener and cache configuration lives in the module's own configuration class, so it disappears when the module is `off` or `http`.
- REST controllers live in the `-impl` of the module that owns the resource, under that module's path prefixes (section 8.2), so the gateway can route a path to whichever Deployment runs the module.

### 5.2 Module catalogue

| Module | Responsibility | REST prefixes | Sync dependencies (`-api`) | Publishes | Consumes |
|---|---|---|---|---|---|
| `identity` | Users, credentials, OTP, OIDC authorization server, roles and permissions, back-office invitations, data export/delete orchestration | `/oauth2`, `/login`, `/.well-known`, `/api/v1/auth`, `/api/v1/users`, `/api/v1/me` | none | `UserRegistered`, `UserDeleted`, `RoleChanged` | none |
| `settings` | Branding, locale, business rules, content pages, feature flags | `/api/v1/settings`, `/api/v1/content` | none | `SettingsChanged` | none |
| `geo` | Zones, address geocoding, routing and ETA, maps provider SPI | `/api/v1/geo`, `/api/v1/zones` | none | `ZoneChanged` | none |
| `store` | Stores, branches, hours, pause, menus, items, modifiers, availability, commission config | `/api/v1/stores`, `/api/v1/menus` | `geo`, `settings` | `StoreChanged`, `MenuChanged`, `StoreAvailabilityChanged`, `StorePaymentCollectionChanged` | none |
| `search` | Search and discovery read model in Elasticsearch, home sections | `/api/v1/search`, `/api/v1/discovery` | `geo` | none | `store.*`, `review.*` (rating), `settings.*` (languages) |
| `cart` | Carts, pricing preview | `/api/v1/carts` | `store`, `geo`, `promotion` | none | `store.*` (invalidate) |
| `promotion` | Coupons, store offers, validation, redemption | `/api/v1/coupons`, `/api/v1/offers` | none | `CouponRedeemed`, `CouponReleased` | `order.*` (redeem on confirm, release on cancel) |
| `payment` | Payment intents, gateway SPI, merchant accounts (operator and store-owned), webhooks, refunds, saved card tokens | `/api/v1/payments`, `/api/v1/payment-webhooks` | none | `PaymentSucceeded`, `PaymentFailed`, `RefundSucceeded` | `order.*` (refund on cancel), `store.*` (collection mode), `ledger.*` (credit limit) |
| `order` | Order placement, state machine, timeline, support actions, SSE stream | `/api/v1/orders` | `cart`, `store`, `promotion`, `payment`, `geo` | `OrderPlaced`, `OrderConfirmed`, `OrderAccepted`, `OrderReady`, `OrderPickedUp`, `OrderDelivered`, `OrderCancelled`, `OrderRefunded` | `payment.*`, `dispatch.*` |
| `dispatch` | Delivery jobs, rider matching, offers, proof of delivery | `/api/v1/deliveries`, `/api/v1/dispatch` | `rider`, `geo` | `RiderAssigned`, `DeliveryPickedUp`, `DeliveryCompleted`, `DeliveryFailed` | `order.*` |
| `rider` | Rider profiles, documents, online state, live location, earnings view | `/api/v1/riders`, `/api/v1/rider` | `geo` | `RiderStatusChanged` | `dispatch.*`, `ledger.*` |
| `review` | Ratings, reviews, replies, moderation | `/api/v1/reviews` | `order` | `ReviewPublished`, `ReviewHidden` | none |
| `notification` | Templates, channels (email, SMS, push), preferences, delivery log, device tokens | `/api/v1/notifications`, `/api/v1/devices` | `identity` | none | events from all modules |
| `ledger` | Double-entry ledger, commissions, store receivables and credit limits, netted payouts, receipts, reports | `/api/v1/ledger`, `/api/v1/payouts`, `/api/v1/receipts` | none | `PayoutSettled`, `StoreCreditLimitExceeded`, `StoreBalanceSettled` | `order.*`, `payment.*`, `dispatch.*`, `promotion.*` |

The synchronous dependency graph is acyclic: `order → cart → store → geo/settings`, `order → promotion/payment`, `dispatch → rider → geo`, `review → order`, `notification → identity`. Back-references go through events (for example `dispatch` tells `order` about pickup with `DeliveryPickedUp`, never by calling `OrderApi`).

### 5.3 Order placement flow

```
Customer ─POST /api/v1/orders (Idempotency-Key)─► order
  order ─► cart.checkoutSnapshot(cartId)                 (items, store, address)
  order ─► store.validateOrder(storeId, lines, time)     (open? items available? current prices)
  order ─► geo.quote(storeLocation, address)              (in zone? fee, distance, ETA)
  order ─► promotion.validate(couponCode, context)        (discount, funding split)
  order    computes totals, persists order + outbox(OrderPlaced) in one Mongo transaction
  if card: order ─► payment.resolveMerchant(storeId)       (operator account, or store's own; see 5.4)
           order ─► payment.createIntent(orderId, merchantAccountId, amount) ─► client confirms with gateway
           payment webhook ─► PaymentSucceeded ─► order: CONFIRMED (+ OrderConfirmed)
  if cash: order: CONFIRMED immediately (+ OrderConfirmed)
OrderConfirmed ─► promotion (redeem), notification (store alert, customer receipt), dispatch (prepare job)
Store accepts ─► OrderAccepted ─► dispatch starts matching (operator-fleet stores)
```

The order snapshots everything it needs at placement (prices, commission rate, store name, address), so later changes to menus or commission never alter past orders and `ledger` never needs to call `store`.

### 5.4 Payment collection: operator or store merchant account

Every payment intent is created against a **merchant account**. The operator has one (the default); a store can have its own when the operator allows it.

```
payment_merchant_accounts { id, owner: OPERATOR | STORE, storeId?, gateway, credentialsRef (encrypted),
                            status: PENDING_VERIFICATION | ACTIVE | SUSPENDED | REVOKED }
store_stores.paymentCollection { mode: OPERATOR | DIRECT, allowedBy, allowedAt, creditLimit }
```

- `settings` holds the global switch (`payments.directCollection.enabled`, default `false`) and the default credit limit.
- `store` owns the per-store permission (`mode`), changed only through an admin endpoint guarded by `store:payment-collection:manage`; changes emit `StorePaymentCollectionChanged`.
- `payment` owns the store's merchant account. Credentials are encrypted with the PII key (or held in an external secret store through the gateway SPI) and verified by a test call before becoming `ACTIVE`.
- At order placement, `order` asks `payment.resolveMerchant(storeId)`. It returns the store's account only when the global switch is on, the store's mode is `DIRECT`, the account is `ACTIVE` and the store is not over its credit limit; otherwise it returns the operator's account. The chosen account is snapshotted on the order, so refunds always go to the same account.
- `ledger` posts different entries depending on the snapshot. Operator-collected: cash in to the operator, payable to the store for its share. Direct-collected: cash in to the store, **receivable from the store** for commission, operator delivery fee, service fee and rider tips. Settlement nets payables and receivables per store per period and reports who pays whom.
- `ledger` tracks each store's outstanding receivable. When it passes the credit limit, it emits `StoreCreditLimitExceeded`; `payment` marks the store account `SUSPENDED` (collection falls back to the operator) and `notification` alerts the store and finance admins. `StoreBalanceSettled` reverses it.
- Webhooks from a store's account are routed by account ID (`/api/v1/payment-webhooks/{accountId}`) and verified with that account's signing secret.
- Later option: gateway-native split payments, adding a `SPLIT` mode to the same model.

### 5.5 Dispatch algorithm (v1)

1. On `OrderAccepted`, create a job with target pickup time = accepted time + prep estimate − travel time to store.
2. Candidate riders: online, in the order's zone, within radius R (default 5 km) of the store, from Redis GEO (`GEOSEARCH`).
3. Score = w1·distance-to-store + w2·active-jobs + w3·(1 − acceptance-rate) + w4·idle-time bonus. Weights in settings.
4. Offer to the best rider; lock the rider in Redis (`SET NX PX`) for the offer timeout (30 s). On decline or timeout, move to the next; widen radius after N failures; alert admins after the configured limit.
5. All transitions are persisted and emitted as events; the admin live map subscribes over SSE.

## 6. Data design (MongoDB)

### 6.1 Naming and isolation

- Collections: `<module>_<plural>`, for example `order_orders`, `order_outbox`, `store_menus`, `ledger_entries`.
- Each module has its own `MongoTemplate` (bean `<module>MongoTemplate`) built from `foodstack.modules.<module>.mongodb.uri`, falling back to `foodstack.mongodb.uri`. In the monolith every module points at one database `foodstack`; extracting a module means giving it its own URI.
- No cross-module queries, joins (`$lookup`) or transactions. A transaction never spans two modules.
- MongoDB must run as a replica set (single-node replica set in dev) because the outbox needs multi-document transactions.

### 6.2 Conventions

| Concern | Convention |
|---|---|
| IDs | Time-ordered string IDs (UUIDv7) generated in the application; human order number `FS-YYMMDD-XXXXX` for support |
| Money | `Money { long minor; String currency }` (minor units); never floating point |
| Time | `Instant` in UTC; store-local times (opening hours) as `LocalTime` + store timezone |
| Translated text | `Map<String,String>` keyed by language tag, with fallback to the default language |
| Concurrency | `@Version` optimistic locking on aggregates |
| Geo | GeoJSON with `2dsphere` indexes (stores, zones) |
| Soft delete | `deletedAt` for business records; hard delete only for data-deletion requests |
| Audit | `createdAt`, `createdBy`, `updatedAt`, `updatedBy` on every document; back-office writes also go to `identity_audit_log` |
| PII | Phone and email encrypted with AES-GCM (key from Kubernetes Secret or external KMS); a keyed HMAC blind index supports lookup |

### 6.3 Migrations (Liquibase)

- Each module ships `db/changelog/<module>/db.changelog-master.yaml` using the `liquibase-mongodb` change types (`createCollection`, `createIndex`, `insertMany`, `runCommand`).
- Liquibase tracking collections are per module: `<module>_databasechangelog` and `<module>_databasechangeloglock`, so migrations of one module never block another and travel with it when extracted.
- `platform-mongo` provides `ModuleLiquibaseRunner`, registered by each module's configuration class, so only `local` modules migrate.
- Production: migrations run in a Helm `pre-install,pre-upgrade` hook Job (`foodstack-server --foodstack.migrate-only=true`). Dev and tests: they run at startup.
- Migrations must be backward compatible with the previous release (expand, then contract in a later release) so rolling upgrades work.

## 7. Events (Kafka)

### 7.1 Topics and envelope

| Item | Convention |
|---|---|
| Topic | One per publishing module: `foodstack.<module>.events` (for example `foodstack.order.events`) |
| Key | Aggregate ID, so events of one order stay ordered |
| Partitions | 12 by default (configurable); replication factor 3 in production |
| Envelope | CloudEvents 1.0 JSON: `id`, `source` (`foodstack/<module>`), `type` (`foodstack.order.OrderPlaced.v1`), `time`, `subject` (aggregate ID), `traceparent`, `data` |
| Schema | Event payloads are records in `<module>-api/events`, versioned by type suffix; changes are additive, breaking changes get a new `vN` type |
| Consumer group | `foodstack-<consumingModule>` |
| Retry | Spring Kafka retry with backoff (3 attempts), then `foodstack.<module>.events.dlt`; admin UI lists DLT messages for replay |
| Retention | 7 days (topic), DLT 30 days |

### 7.2 Transactional outbox and idempotent inbox

```
[ module transaction ]  write aggregate  +  insert <module>_outbox {eventId, topic, key, payload, status=NEW}
[ relay ]               leader per module (Redis lock) polls NEW rows every 100 ms, batch 500,
                        sends with idempotent producer (acks=all), marks SENT; TTL index removes SENT after 7 days
[ consumer ]            per message: if eventId in <consumer>_inbox → skip; else handle + insert inbox in one transaction
```

This gives at-least-once delivery with effectively-once processing, and no event is lost if Kafka is briefly unavailable. Events go through Kafka in every topology, including the monolith, so behaviour never changes when a module is split out.

## 8. API design

### 8.1 Conventions

- Base path `/api/v1`; resources are plural nouns; JSON with camelCase; ISO-8601 times.
- OpenAPI 3.1 generated by springdoc per module and merged into one document at `/api/v1/openapi.json`; the Angular API client is generated from it in CI.
- Errors: RFC 9457 `application/problem+json` with `code`, `traceId` and `errors[]` for validation, matching Module-Switch's wire format.
- Pagination: cursor-based (`?cursor=&limit=`), response `{ items, nextCursor }`.
- `Idempotency-Key` header required on order placement, payments and refunds; responses cached 24 h in Redis under `foodstack:<module>:idem:<key>`.
- `ETag` / `If-None-Match` on settings, branding and menus; `If-Match` for back-office edits to prevent lost updates.
- Rate limits per IP and per user (Redis token bucket), stricter on OTP and login.
- Additive changes only within `v1`; deprecations announced with a `Deprecation` header.

### 8.2 Routing in split deployments

Every REST prefix belongs to exactly one module (section 5.2). The Helm chart generates Gateway API `HTTPRoute` rules from that map, sending each prefix to the Deployment that runs the module locally. In the monolith all prefixes go to one Service.

### 8.3 Real-time

- `GET /api/v1/orders/{id}/stream` and `GET /api/v1/dispatch/live` (admin) are SSE endpoints on virtual threads.
- Fan-out across replicas uses Redis Pub/Sub channels `foodstack:<module>:rt:<topic>`; the replica that holds the SSE connection forwards messages. Heartbeat every 15 s; clients reconnect with `Last-Event-ID`.
- Rider location: `POST /api/v1/rider/location` every 10 s → Redis GEO (latest) + Pub/Sub (live) + sampled history in `rider_locations` with a TTL index (retention from settings, default 30 days).
- Push: `notification` sends FCM for devices with the app closed.

## 9. Security

### 9.1 Identity and access

- `identity` embeds Spring Authorization Server. Clients: `web` and `backoffice` (public, Authorization Code + PKCE), future mobile apps (same), internal service clients (client credentials, for jobs).
- The login UI is served by `identity` and themed from `settings` branding. It supports phone OTP (OTP stored hashed in Redis with 5 minute TTL, attempt limits), email and password (Argon2id), social login (OIDC federation), and TOTP for back-office users.
- Access tokens: JWT signed with ES256, 10 minute lifetime; refresh tokens rotated, 30 days for customers and 12 hours for back office. Signing keys come from a Kubernetes Secret and are published through JWKS; rotation keeps the previous key valid.
- Token claims: `sub`, `roles`, `perms`, `storeIds` (store staff), `riderId`.
- Every module is an OAuth2 resource server validating JWTs locally (JWKS cached). Authorization uses method security with permissions such as `order:refund` and store-scoped checks (`storeIds` contains the target store).
- Module-to-module: Module-Switch forwards the `Authorization` header, so the callee enforces the same user permissions. The internal port (8081) also requires `X-Module-Switch-Token`, and Istio `AuthorizationPolicy` restricts it to FoodStack service accounts.
- Web apps keep the access token in memory only and use refresh-token rotation; strict CSP, `SameSite` cookies for the refresh token on the identity origin.

### 9.2 Platform security

| Control | Implementation |
|---|---|
| Transport | TLS at the Gateway (cert-manager); Istio ambient STRICT mTLS between all pods |
| Network | Default-deny `NetworkPolicy`; explicit allows per component; egress allow-list for external providers |
| Pods | Non-root, read-only root filesystem, dropped capabilities, `seccompProfile: RuntimeDefault`, Pod Security Standard `restricted` |
| Secrets | Referenced by `existingSecret`; optional External Secrets Operator integration; never baked into images or values |
| Supply chain | SBOM (CycloneDX) per image, Trivy scan in CI, cosign keyless signing, Dependabot, CodeQL |
| Payments | Gateway-hosted card fields; only gateway tokens stored; webhook signatures verified per merchant account; store merchant credentials encrypted and write-only through the API; idempotent handling |
| Data | PII field encryption, blind indexes, retention jobs, data export and deletion workflows |
| Audit | Back-office writes and logins logged with actor, before and after |

## 10. Caching (Redis)

| Use | Key pattern | TTL / notes |
|---|---|---|
| Spring Cache (menus, store cards, settings, branding, zones) | `foodstack:<module>:cache:<name>:<key>` | 1 to 10 min; evicted on the module's own change events |
| Idempotency | `foodstack:<module>:idem:<key>` | 24 h |
| Rate limiting | `foodstack:identity:rl:<scope>:<id>` | token bucket |
| OTP | `foodstack:identity:otp:<phoneHash>` | 5 min |
| Rider live location | `foodstack:rider:geo` (GEO set) | refreshed on each ping; stale riders pruned |
| Dispatch offer locks | `foodstack:dispatch:offer:<riderId>` | offer timeout |
| Outbox relay leader lock | `foodstack:<module>:outbox:leader` | 10 s, renewed |
| Real-time fan-out | `foodstack:<module>:rt:*` (Pub/Sub) | none |

Redis is a cache and coordination store only; losing it never loses business data. Production uses Redis with replicas (or a managed equivalent); Redis Cluster is supported because every key family has its own hash tag.

## 11. Search (Elasticsearch)

- Indices prefixed by module: `search_stores_v<N>` and `search_dishes_v<N>`, accessed through aliases `search_stores` and `search_dishes`; reindex by building a new version and swapping the alias.
- Built only from events (`StoreChanged`, `MenuChanged`, `StoreAvailabilityChanged`, `ReviewPublished`), never by reading another module's database; a full rebuild job replays from `store` through its API.
- Per-language fields with language analyzers (including Arabic and Urdu), `search_as_you_type` for autocomplete, `geo_point` for distance sorting, and zone IDs for filtering.
- Ranking: open now, distance, rating, ETA, plus admin boosts.
- `SearchEngine` SPI in `search-impl`; the Elasticsearch Java client is the default adapter.

## 12. Geo and maps

- `geo` owns zones (GeoJSON polygons, `geo_zones`), fee rules and quoting.
- Provider SPI: `GeocodingProvider`, `RoutingProvider`, `TileConfig`. Default adapter: Nominatim and OSRM (operator self-hosts or uses a hosted provider; public instances are only for demos). Optional Google Maps adapter.
- ETA = prep estimate + routing duration × zone traffic factor (configurable); quotes are cached briefly.

## 13. Front-end design

### 13.1 Workspace

```
frontend/
├─ angular.json, package.json        Angular (latest stable at M0), pnpm
├─ projects/
│  ├─ web/                           customer app: SSR + hydration, PWA
│  ├─ backoffice/                    admin, store and rider areas; rider area installable PWA
│  └─ libs/
│     ├─ ui/                         design system on Angular Material 3: tokens, theming, shared components
│     ├─ api-client/                 generated from OpenAPI (typed services and models)
│     ├─ auth/                       OIDC PKCE client, guards, interceptors, token refresh
│     ├─ i18n/                       runtime translations, RTL handling, locale formatting
│     ├─ maps/                       MapLibre GL wrapper (pins, polygons, live markers)
│     └─ realtime/                   SSE client with reconnect and Last-Event-ID
```

### 13.2 Patterns

- Standalone components, signals and zoneless change detection; NgRx SignalStore for feature state; lazy-loaded routes per area and feature.
- **Runtime branding:** on boot the app loads `/api/v1/settings/branding` and writes Material 3 design tokens as CSS custom properties (colour palettes generated from the operator's primary and secondary colours, typography, logo URLs). Components never hard-code colours.
- **Runtime i18n:** Transloco with translations fetched per language, because languages are chosen at runtime. `dir="rtl"` is set from the active locale; styles use logical properties.
- **Runtime config:** each nginx image serves `/config.json` built from environment variables at container start (API URL, OIDC issuer, maps tile URL), so one image works for every operator.
- Accessibility: Material components plus axe checks in CI; light and dark themes.
- Testing: unit tests with Vitest, end-to-end with Playwright against the `dev` Helm install.

### 13.3 Images

`ilyaslabs/foodstack-web` runs the SSR Node server; `ilyaslabs/foodstack-backoffice` is static assets on `nginx-unprivileged`. Both are non-root with read-only filesystems.

## 14. Build, CI/CD and releases

### 14.1 Backend build

- Parent POM inherits `spring-boot-starter-parent` 4.x, imports `module-switch-bom`, sets `java.version=25`, uses `${revision}` with the flatten plugin (as `ilyaslabs/cicd` `mvn-release.yml` passes `-Drevision=<tag>`).
- All `-api`, `-impl`, `platform-*` and `foodstack-bom` jars are published to Maven Central, so operators can build a custom server that adds or replaces modules.
- `foodstack-server` image: multi-stage Dockerfile, Spring Boot layered jar on a JRE 25 base, non-root, multi-arch (amd64, arm64), virtual threads on.

### 14.2 Workflows

| Workflow | Trigger | Does |
|---|---|---|
| `pr-checks.yml` | Pull request | Calls `ilyaslabs/cicd/.github/workflows/mvn-pr-checks.yml@main`; frontend lint, test and build; Helm lint and `kubeconform`; `ModuleRules.verify`; Trivy filesystem scan |
| `snapshot-release.yml` | Push to `main` | Calls `mvn-snapshot-release.yml`; pushes `:edge` images |
| `release.yml` | GitHub release `vX.Y.Z` | Calls `mvn-release.yml` (Maven Central); builds, signs and pushes images to Docker Hub and GHCR; packages the Helm chart and pushes it to GHCR as an OCI artifact |
| `e2e.yml` | Nightly and before release | kind cluster, `values-dev.yaml` and `values-split.yaml` installs, Playwright suite, k6 smoke test |

A reusable `docker-release.yml` and `helm-release.yml` will be added to `ilyaslabs/cicd` so other ilyasLabs projects can share them.

### 14.3 Testing strategy

| Level | Tooling | Scope |
|---|---|---|
| Unit | JUnit 5, AssertJ | Domain logic, state machines, pricing, ledger balancing |
| Architecture | `ModuleRules.verify`, ArchUnit | Module boundaries, package rules, collection prefix rule |
| Integration | Spring Boot tests, Testcontainers (MongoDB replica set, Redis, Kafka, Elasticsearch) | Each module with its real datastores |
| Split topology | Two app contexts, one module in `http` mode | Every module's API works remotely exactly as locally |
| Contract | OpenAPI diff in CI | No breaking change within `/api/v1` |
| End-to-end | Playwright | Main journeys from the PRD |
| Load | k6 | NFR targets before each release |

## 15. Kubernetes and Helm

### 15.1 Chart layout

```
k8s/helm/foodstack/
├─ Chart.yaml
├─ values.yaml            production defaults: monolith, external datastores, Istio ambient
├─ values-split.yaml      order, dispatch, search in their own Deployments
├─ values-dev.yaml        in-cluster MongoDB (single-node replica set), Redis, Kafka (KRaft), Elasticsearch, Mailpit
└─ templates/
   ├─ server/             Deployment, Service, HPA, PDB, ServiceAccount, ConfigMap per entry in .Values.server.deployments
   ├─ web/, backoffice/   Deployment, Service, HPA, PDB
   ├─ migrations-job.yaml pre-install / pre-upgrade hook
   ├─ gateway/            Gateway, HTTPRoute (generated from the module → prefix map), certificates
   ├─ mesh/               namespace ambient label, PeerAuthentication STRICT, AuthorizationPolicy, waypoint
   ├─ networkpolicies.yaml
   └─ dev/                datastore StatefulSets (rendered only with values-dev.yaml)
```

### 15.2 Module topology from values

```yaml
server:
  image: ilyaslabs/foodstack-server
  deployments:
    core:                                   # default monolith: every module local
      modules: [identity, settings, geo, store, search, cart, promotion, payment,
                order, dispatch, rider, review, notification, ledger]
      replicas: 3
# values-split.yaml overrides:
#   core:     { modules: [identity, settings, geo, store, cart, promotion, payment, rider, review, notification, ledger] }
#   order:    { modules: [order],    replicas: 3, hpa: { max: 20 } }
#   dispatch: { modules: [dispatch], replicas: 2 }
#   search:   { modules: [search],   replicas: 2 }
```

The chart computes Module-Switch configuration for each Deployment: its own modules are `local` and exposed on port 8081; every other module is `http` with `url: http://foodstack-<deployment>.<namespace>.svc.cluster.local:8081`; modules nobody runs are `off`. It also generates the `HTTPRoute` rules that send each module's REST prefixes to the right Service. Operators never edit Module-Switch YAML by hand.

### 15.3 Networking, mesh and load balancing

- Services: `foodstack-<deployment>` (ports `http` 8080 public API, `internal` 8081 Module-Switch), `foodstack-web`, `foodstack-backoffice`. Cluster-local DNS names such as `foodstack-order.foodstack.svc.cluster.local`.
- Istio ambient: the namespace is labelled `istio.io/dataplane-mode=ambient`; ztunnel gives mTLS and L4 authorization with no sidecars. A waypoint proxy for the namespace gives L7 load balancing (least request), retries for idempotent routes and per-route timeouts.
- `AuthorizationPolicy`: port 8081 only from FoodStack server service accounts; public port only from the Gateway; datastores only from the server.
- Ingress: Gateway API `Gateway` (Istio implementation) with hosts for `web`, `backoffice` and `api`, TLS from cert-manager; HTTP→HTTPS redirect; HSTS.
- Without Istio (`mesh.enabled=false`) the chart still works, using any Gateway API implementation and NetworkPolicies, but without mTLS.

### 15.4 Reliability and scaling

- Readiness and liveness from Spring Boot actuator groups; startup probe for slow starts; graceful shutdown (30 s) drains SSE connections.
- HPA on CPU and on request rate (Prometheus adapter, optional). PDB `minAvailable: 1` (2 for `order` in split). Topology spread across zones and nodes.
- Resource requests and limits set per Deployment; JVM sized with `MaxRAMPercentage`.
- Datastores in production: managed or operator-run MongoDB replica set, Redis with replicas, Kafka with 3 brokers, Elasticsearch with 3 nodes. Connection details come from Secrets referenced in values.

### 15.5 Observability

- Spring Boot with Micrometer and the OpenTelemetry exporter: traces (W3C context through HTTP, Module-Switch calls and Kafka headers), metrics (including `module.switch.call`, outbox lag, consumer lag, dispatch timings, order funnel), structured JSON logs with `traceId`.
- `foodstack-observability` chart (optional): OpenTelemetry Collector, Prometheus, Grafana with FoodStack dashboards (golden signals per module, order funnel, dispatch, Kafka lag), Tempo, Loki, and alert rules (error rate, latency, outbox lag, DLT growth, certificate expiry).

## 16. Configuration reference (excerpt)

```yaml
foodstack:
  mongodb:
    uri: ${MONGODB_URI}                     # default for every module
  modules:
    order:
      mongodb.uri: ${ORDER_MONGODB_URI:}    # optional: dedicated database when extracted
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP}
    topic-partitions: 12
  redis:
    url: ${REDIS_URL}
  search:
    engine: elasticsearch
    url: ${ELASTICSEARCH_URL}
  security:
    issuer: https://api.example.com
    pii-key: ${PII_KEY}                     # from Secret
  migrate-only: false
module-switch:                              # generated by Helm per Deployment
  modules: { … }
  expose: { port: 8081, modules: [ … ], token: ${INTERNAL_TOKEN} }
  propagate-headers: [Authorization, Accept-Language]
```

## 17. Risks and open technical questions

| # | Item | Proposal |
|---|---|---|
| T1 | Kafka adds operational weight for small pilots | `values-dev.yaml` runs single-node KRaft; docs list managed Kafka options; outbox keeps the app working while Kafka is briefly down |
| T2 | Spring Authorization Server support for phone OTP login | Implement OTP as a login step in the hosted login page (not a custom grant), keeping standard Authorization Code + PKCE for all clients |
| T3 | Choice of first card gateway and SMS provider | Decide at M2 (PRD open questions) |
| T4 | Public OSM services have strict usage policies | Default chart ships optional self-hosted Nominatim and OSRM sub-components; docs explain data import per region |
| T5 | Licences of third-party servers (Liquibase FSL, Elasticsearch) | Document in `docs/licensing.md`; operators run unmodified upstream software |
| T6 | Module-Switch is new (0.x) | FoodStack pins a released version and contributes fixes upstream; split-topology tests run in CI |
