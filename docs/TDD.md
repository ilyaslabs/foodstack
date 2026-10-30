# FoodStack: Technical Design Document (TDD)

| | |
|---|---|
| Status | Draft v1, agreed in grooming with ilyas (2026-10-27, last updated 2026-10-29) |
| Owner | ilyas (decisions), Claude (architecture draft) |
| Related | [BRD](BRD.md), [PRD](PRD.md), [Module-Switch](https://github.com/ilyaslabs/module-switch), [Module-Switch example](https://github.com/ilyaslabs/module-switch-example) |

## 1. Goals and principles

1. **Modular monolith first, services by configuration.** Every backend module follows Module-Switch rules, so it can run `local`, `http` or `off` without code changes.
2. **Each module owns its data.** Its MongoDB collections, Redis keys, Elasticsearch indices, Kafka topic and Liquibase changelog all carry the module name as a prefix. Extracting a module means pointing it at its own datastore, not untangling shared tables.
3. **Synchronous calls for queries and validation, Kafka events for consequences.** A module calls another module's `-api` when it needs an answer now; it publishes an event when something happened that others may react to.
4. **Pure REST.** Web apps and future native apps use the same documented API.
5. **Secure by default.** mTLS everywhere, least privilege, no secrets in images, PII encrypted.
6. **One server first, Kubernetes when needed.** The modular monolith launches on one server with Docker Compose; the optional Helm chart runs on any conformant Kubernetes. No cloud-specific services in either.

## 2. Decision record (from grooming)

| # | Topic | Decision |
|---|---|---|
| D1 | Deployment model | One operator per deployment (white-label), not multi-tenant |
| D2 | Language and framework | Java 25, Spring Boot 4, Module-Switch |
| D3 | Build | Maven multi-module, `${revision}` versioning, published to Maven Central via `ilyaslabs/cicd` reusable workflows |
| D4 | Persistence | MongoDB, one database per module (`foodstack_<module>`) on a shared cluster, collections also prefixed `<module>_` |
| D5 | Migrations | Liquibase 5 with `liquibase-mongodb`, one changelog per module (Liquibase 5 is FSL licensed: free to use, only competing Liquibase products are restricted) |
| D6 | Cache | Redis, keys prefixed `foodstack:<module>:` |
| D7 | Search | Elasticsearch 9 behind a `search` module SPI, so an OpenSearch adapter remains possible |
| D8 | Events | **Kafka from day one**, with a transactional outbox per module |
| D9 | Identity | Own `identity` module on Spring Authorization Server (OIDC, JWT): email and password, email one-time code, social login. No SMS in v1, so phone OTP comes later |
| D10 | Real time | Server-Sent Events for web, FCM push for devices |
| D11 | Maps | Provider SPI; default OpenStreetMap stack (MapLibre, Nominatim, OSRM); Google Maps adapter |
| D12 | Front-end | Angular workspace, two apps (`web`, `backoffice`), Angular Material 3 with design tokens, no micro-frontends |
| D13 | Repository | Monorepo: backend, frontend, `k8s/helm`, docs |
| D14 | Artifacts | Maven `io.github.ilyaslabs:foodstack-*`; images `ilyaslabs/foodstack-server`, `ilyaslabs/foodstack-web`, `ilyaslabs/foodstack-backoffice` on Docker Hub, mirrored to GHCR |
| D15 | Kubernetes | **Optional.** Helm chart; managed datastores in production, in-cluster datastores with `values-dev.yaml`; one `topology` value (`monolith` default, `split`, `microservices`, `custom`) generates every Deployment, route and Module-Switch setting (section 18.2) |
| D16 | Service mesh | Istio ambient mode (ztunnel mTLS, waypoints for L7), Gateway API for ingress |
| D17 | Observability | OpenTelemetry; optional Prometheus, Grafana, Tempo and Loki stack |
| D18 | Launch deployment | **Unit deployer**: `unit-deployer-<version>.zip` with the jar, Docker Compose and install, start, stop and restart scripts running everything on one server (section 17) |
| D19 | Media | S3-compatible object storage through a `media` module, with resizing and WebP compression on upload (section 13) |
| D20 | Time | Stored in UTC; the API exchanges date-times as UTC epoch seconds |
| D21 | Local development | One-click run with Testcontainers; `test-seed` profile loads demo data (section 16) |
| D22 | Sharding | Every collection designed with a shard key from day one (section 6.3) |
| D23 | Notifications | In-app, email and push; no SMS in v1 |
| D24 | API gateway | No separate gateway service. The Envoy-based Istio Gateway is the API gateway on Kubernetes (Caddy on a single server): TLS, routing, JWT validation, retries, timeouts, CORS, size limits and **global edge rate limiting**. Kong or APISIX can be added in front later if partner API products are needed |

## 3. System context

```
                         ┌──────────── one server (unit deployer) or Kubernetes cluster ───────────┐
 Customer browser ──┐    │                                                                          │
 Store tablet ──────┤    │  Caddy (server) or     ──► foodstack-web (Angular SSR)                    │
 Rider phone (PWA) ─┼────┼─►  Gateway API (k8s)   ──► foodstack-backoffice (nginx, Angular)          │
 Admin desktop ─────┤    │                      └─► foodstack-server (Spring Boot, all modules)      │
 Future mobile app ─┘    │                              │  ▲ module-switch http (internal :8081)     │
                         │                              ▼  │   when split into several Deployments   │
                         │   MongoDB   Redis   Kafka   Elasticsearch   S3 (RustFS on one server)         
                         └──────────────────────────────────────────────────────────────────────────┘
 External: payment gateway, SMTP, FCM, map tiles / geocoding / routing
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
│  ├─ identity/   identity-api/  identity-core/  identity-impl/  identity-web/
│  ├─ settings/   settings-api/  settings-core/  settings-impl/  settings-web/
│  ├─ media/      media-api/  media-core/  media-impl/  media-web/      (S3 object storage adapter)
│  ├─ geo/        geo-api/  geo-core/  geo-impl/  geo-web/        geo-provider-osm/   geo-provider-google/
│  ├─ store/      store-api/  store-core/  store-impl/  store-web/
│  ├─ search/     search-api/  search-core/  search-impl/  search-web/     (Elasticsearch adapter inside core)
│  ├─ cart/       cart-api/  cart-core/  cart-impl/  cart-web/
│  ├─ promotion/  promotion-api/  promotion-core/  promotion-impl/  promotion-web/
│  ├─ payment/    payment-api/  payment-core/  payment-impl/  payment-web/    payment-gateway-card/  (first card adapter)
│  ├─ order/      order-api/  order-core/  order-impl/  order-web/
│  ├─ dispatch/   dispatch-api/  dispatch-core/  dispatch-impl/  dispatch-web/
│  ├─ rider/      rider-api/  rider-core/  rider-impl/  rider-web/
│  ├─ review/     review-api/  review-core/  review-impl/  review-web/
│  ├─ notification/ notification-api/  notification-core/  notification-impl/  notification-web/ (in-app, email, FCM channel adapters)
│  └─ ledger/     ledger-api/  ledger-core/  ledger-impl/  ledger-web/
├─ app/
│  └─ foodstack-server/            @SpringBootApplication, depends on every *-core, *-impl and *-web; application*.yaml; Dockerfile
├─ frontend/                       Angular workspace (section 14)
├─ deploy/
│  └─ unit-deployer/               docker-compose.yml, scripts, Maven assembly for unit-deployer-<version>.zip (section 17)
├─ k8s/                            optional Kubernetes deployment
│  └─ helm/
│     ├─ foodstack/                main chart (section 18)
│     └─ foodstack-observability/  optional monitoring chart
├─ docs/                           BRD, PRD, TDD, ADRs, guides
├─ integration-tests/              the only full-application tests; every scenario runs as monolith and as microservices
├─ tools/                          load tests (k6), seed data, scripts
└─ .github/workflows/              CI calling ilyaslabs/cicd reusable workflows
```

Java packages: `io.github.ilyaslabs.foodstack.<module>.api`, `.core`, `.impl` and `.web`. Maven artifacts: `foodstack-<module>-api`, `-core`, `-impl`, `-web`.

## 5. Backend module design

### 5.1 Anatomy of a module

```
order-api/                          @ModuleApi("order")  (package-info.java)
  OrderApi.java                     interface; every param @Param; no overloads
  model/  OrderView, OrderLine…     records (Jackson-serializable)
  events/ OrderEvent (sealed) + OrderPlaced, OrderAccepted…   records, published to Kafka
order-core/                         @Module("order")     (package-info.java on ...order.core)
  OrderModuleConfiguration.java     @EnableMongoRepositories(mongoTemplateRef = "orderMongoTemplate"),
                                    MongoTemplate bean, Liquibase runner, Kafka listeners, caches
  application/                      use-case services: PlaceOrderService, OrderQueries, SupportActions…
  domain/                           aggregates, state machine, pricing, domain services
  persistence/                      @Document("order_orders") entities, repositories
  messaging/                        outbox publisher, event consumers
  src/main/resources/db/changelog/order/db.changelog-master.yaml
order-impl/                         @Module("order")     (package-info.java on ...order.impl)
  OrderApiImpl.java                 implements OrderApi by calling core application services
  OrderApiMapper.java               maps core results to api records; core errors to ModuleException
order-web/                          @Module("order")     (package-info.java on ...order.web)
  OrderController.java…             REST controllers under /api/v1/orders…, SSE endpoints
  dto/                              public request/response records (separate from internal api records)
  OrderWebMapper.java               maps between REST DTOs and core application services
```

**What goes where.** Each module has four Maven sub-modules. `impl` and `web` are two thin doors into the same `core`:

| Sub-module | Contains | May depend on |
|---|---|---|
| `<m>-api` | The module's *internal Java contract* for other modules: `@ModuleApi` interfaces, DTO records, event records. No Spring beans, no controllers, no logic | `module-switch-api`, `platform-core` |
| `<m>-core` | **All business logic and data:** application services, domain, persistence, messaging (outbox, consumers), Liquibase changelog, configuration. Knows nothing about HTTP or about being called by other modules | own `-api` (event and shared records), other modules' `-api`, `platform-*` |
| `<m>-impl` | Only the implementation of the module's `-api`: delegates to `core` application services and maps results and errors. No logic, no data access | own `-api`, the `application` package of own `-core` |
| `<m>-web` | The module's **public REST API**: controllers, SSE endpoints, REST request/response DTOs, mapping, OpenAPI annotations. No logic, no data access | the `application` package of own `-core`, `platform-*` |

- **No duplicated logic:** every business rule and database operation exists once, in `-core`. Both doors call the same application services.
- The public REST DTOs in `-web` are separate from the internal records in `-api`, so the public API and the internal contract evolve independently.
- `core`, `impl` and `web` packages are all declared `@Module("<m>")`. Module-Switch accepts several implementation packages per module, so all three load only where the module is `local`, and the gateway routes the module's paths to that Deployment.
- ArchUnit rules in CI: `-impl` and `-web` may use only the `application` package of their own `-core` (never `domain`, `persistence` or `messaging`); `-core` never depends on `-impl` or `-web`; no module may depend on another module's `-core`, `-impl` or `-web` (also checked by `ModuleRules.verify`).

Rules (enforced by Module-Switch at startup and by `ModuleRules.verify("io.github.ilyaslabs.foodstack")` in CI):

- A module depends only on other modules' `-api` artifacts and on `platform-*`.
- Errors cross module boundaries only as `ModuleException` subtypes with stable `code` values (for example `STORE_CLOSED`, `COUPON_EXPIRED`).
- All Spring Data, Kafka listener and cache configuration lives in the module's own configuration class, so it disappears when the module is `off` or `http`.
- REST controllers live in the `-web` of the module that owns the resource, under that module's path prefixes (section 8.2), so the gateway can route a path to whichever Deployment runs the module.

### 5.2 Module catalogue

| Module | Responsibility | REST prefixes | Sync dependencies (`-api`) | Publishes | Consumes |
|---|---|---|---|---|---|
| `identity` | Users, credentials, email one-time codes, OIDC authorization server, roles and permissions, back-office invitations, data export/delete orchestration | `/oauth2`, `/login`, `/.well-known`, `/api/v1/auth`, `/api/v1/users`, `/api/v1/me` | none | `UserRegistered`, `UserDeleted`, `RoleChanged`, `UserTokensRevoked` | none |
| `settings` | Branding, locale, business rules, content pages, feature flags | `/api/v1/settings`, `/api/v1/content` | none | `SettingsChanged` | none |
| `geo` | Store delivery zones with their charges, operator delivery pricing, cities, address geocoding, routing and ETA, maps provider SPI | `/api/v1/geo`, `/api/v1/zones` | none | `ZoneChanged`, `OperatorDeliveryPricingChanged` | none |
| `store` | Stores, branches, delivery mode (self or operator), opening hours, temporary-close switch, menus, items, modifiers, availability, commission config, QR menu codes | `/api/v1/stores`, `/api/v1/menus` | `geo`, `settings` | `StoreChanged`, `MenuChanged`, `StoreAvailabilityChanged`, `StorePaymentCollectionChanged` | none |
| `search` | Search and discovery read model in Elasticsearch, home sections | `/api/v1/search`, `/api/v1/discovery` | `geo` | none | `store.*` (incl. delivery mode), `geo.*` (zones, operator max distance), `review.*` (rating), `settings.*` (languages) |
| `cart` | Carts, pricing preview | `/api/v1/carts` | `store`, `geo`, `promotion` | none | `store.*` (invalidate) |
| `promotion` | Operator and store promotions, coupons, payment-method and merchant-account restrictions, validation, redemption | `/api/v1/coupons`, `/api/v1/offers` | none | `CouponRedeemed`, `CouponReleased` | `order.*` (redeem on confirm, release on cancel) |
| `payment` | Payment intents, gateway SPI, merchant accounts (operator and store-owned), webhooks, refunds, saved card tokens | `/api/v1/payments`, `/api/v1/payment-webhooks` | none | `PaymentSucceeded`, `PaymentFailed`, `RefundSucceeded` | `order.*` (refund on cancel), `store.*` (collection mode), `ledger.*` (credit limit) |
| `order` | Order placement, state machine, timeline, support actions, SSE stream | `/api/v1/orders` | `cart`, `store`, `promotion`, `payment`, `geo` | `OrderPlaced`, `OrderConfirmed`, `OrderAccepted`, `OrderReady`, `OrderPickedUp`, `OrderDelivered`, `OrderCancelled`, `OrderRefunded` | `payment.*`, `dispatch.*` |
| `dispatch` | Delivery jobs for operator and store riders, operator rider matching, offers, proof of delivery | `/api/v1/deliveries`, `/api/v1/dispatch` | `rider`, `geo` | `RiderAssigned`, `DeliveryPickedUp`, `DeliveryCompleted`, `DeliveryFailed` | `order.*` |
| `rider` | Operator riders and store-owned riders, documents, online state, live location, earnings view | `/api/v1/riders`, `/api/v1/rider` | `geo` | `RiderStatusChanged` | `dispatch.*`, `ledger.*` |
| `review` | Ratings, reviews, replies, moderation | `/api/v1/reviews` | `order` | `ReviewPublished`, `ReviewHidden` | none |
| `notification` | Templates, channels (in-app inbox, email, push), preferences, delivery log, device tokens | `/api/v1/notifications`, `/api/v1/devices` | `identity` | none | events from all modules |
| `media` | Uploads, image processing, variants, signed URLs, cleanup | `/api/v1/media` | none | `MediaReady` | none |
| `ledger` | Double-entry ledger, commissions, store receivables and credit limits, netted payouts, receipts, reports | `/api/v1/ledger`, `/api/v1/payouts`, `/api/v1/receipts` | none | `PayoutSettled`, `StoreCreditLimitExceeded`, `StoreBalanceSettled` | `order.*`, `payment.*`, `dispatch.*`, `promotion.*` |

The synchronous dependency graph is acyclic: `order → cart → store → geo/settings`, `order → promotion/payment`, `dispatch → rider → geo`, `review → order`, `notification → identity`. Back-references go through events (for example `dispatch` tells `order` about pickup with `DeliveryPickedUp`, never by calling `OrderApi`).

### 5.3 Order placement flow

```
Customer ─POST /api/v1/orders (Idempotency-Key)─► order
  order ─► cart.checkoutSnapshot(cartId)                 (items, store, address)
  order ─► store.validateOrder(storeId, lines, time)     (open? items available? current prices)
  order ─► geo.quote(storeId, storeLocation, deliveryMode, address)  (self: store zones; operator: operator pricing)
  order ─► promotion.validate(couponCode, context)        (discount, funding split)
  order    computes totals, persists order + outbox(OrderPlaced) in one Mongo transaction
  if card: order ─► payment.resolveMerchant(storeId)       (operator account, or store's own; see 5.4)
           order ─► payment.createIntent(orderId, merchantAccountId, amount) ─► client confirms with gateway
           payment webhook ─► PaymentSucceeded ─► order: CONFIRMED (+ OrderConfirmed)
  if cash: order: CONFIRMED immediately (+ OrderConfirmed)
OrderConfirmed ─► promotion (redeem), notification (store alert, customer receipt), dispatch (prepare job)
Store accepts ─► OrderAccepted{deliveryMode: STORE | OPERATOR (snapshotted at placement), storeRiderId?}
  STORE:    dispatch creates a job for the chosen store rider (or the store marks steps itself)
  OPERATOR: dispatch starts matching operator riders; the delivery charge (operator pricing) goes to the operator
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
- `ledger` posts different entries depending on the snapshot. Operator-collected: cash in to the operator, payable to the store for its share. Direct-collected: cash in to the store, **receivable from the store** for commission, the delivery charge when an operator rider delivered, service fee and rider tips. Settlement nets payables and receivables per store per period and reports who pays whom.
- `ledger` tracks each store's outstanding receivable. When it passes the credit limit, it emits `StoreCreditLimitExceeded`; `payment` marks the store account `SUSPENDED` (collection falls back to the operator) and `notification` alerts the store and finance admins. `StoreBalanceSettled` reverses it.
- Webhooks from a store's account are routed by account ID (`/api/v1/payment-webhooks/{accountId}`) and verified with that account's signing secret.
- Later option: gateway-native split payments, adding a `SPLIT` mode to the same model.
- Card payments can be switched off (`payments.card.enabled`). Integration style per gateway adapter: `HOSTED_PAGE` (redirect to the gateway's hosted payment page; default, keeps card data entirely off our pages) or `EMBEDDED` (gateway fields in our checkout). Saved cards (`payments.savedCards.enabled`) are off by default; when on, only gateway tokens are stored.

### 5.5 Promotions: who creates them and where they apply

- `promotion_promotions { owner: OPERATOR | STORE, storeIds, cityIds, funding: {operatorPct, storePct}, paymentMethods: [CASH, CARD]?, merchantAccount: ANY | OPERATOR_ONLY }`.
- Store owners create promotions for their own store only, always store-funded.
- Operator promotions can be limited to payment methods and to `OPERATOR_ONLY`, meaning they apply only when the payment is collected on the operator's merchant account. `order` passes the payment method and resolved merchant account in the validation context, so the check happens at placement.

### 5.6 Dispatch algorithm (v1)

For `deliveryMode: STORE`, the job goes straight to the chosen store rider (or stays with the store). The steps below apply to operator riders:

1. On `OrderAccepted` with `deliveryMode: OPERATOR`, create a job with target pickup time = accepted time + prep estimate − travel time to store.
2. Candidate riders: online, in the store's city, within radius R (default 5 km) of the store, from Redis GEO (`GEOSEARCH`).
3. Score = w1·distance-to-store + w2·active-jobs + w3·(1 − acceptance-rate) + w4·idle-time bonus. Weights in settings.
4. Offer to the best rider; lock the rider in Redis (`SET NX PX`) for the offer timeout (30 s). On decline or timeout, move to the next; widen radius after N failures; alert admins after the configured limit.
5. All transitions are persisted and emitted as events; the admin live map subscribes over SSE.

## 6. Data design (MongoDB)

### 6.1 Naming and isolation

- Collections: `<module>_<plural>`, for example `order_orders`, `order_outbox`, `store_menus`, `ledger_entries`.
- **Database per module from day one:** each module uses its own database, `foodstack_<module>` (for example `foodstack_order`, `foodstack_store`), on the same MongoDB cluster. Collections keep the module prefix as well (`order_orders`), so names stay unambiguous in tools, logs and backups.
- Each module has its own `MongoTemplate` (bean `<module>MongoTemplate`) built from `foodstack.modules.<module>.mongodb.uri`, falling back to `foodstack.mongodb.uri` (the cluster) with database `foodstack_<module>`.
- **Splitting the application never moves data:** an extracted module keeps using its database on the shared cluster. **Splitting the data** (only when the cluster is the bottleneck or isolation is required) is a whole-database move: `mongodump --db foodstack_<module>` and restore, or MongoDB's live sync tooling for near-zero downtime, then set that module's URI. Its Liquibase history collections live in the same database and move with it.
- No cross-module queries, joins (`$lookup`) or transactions. A transaction never spans two modules.
- MongoDB must run as a replica set (single-node replica set in dev) because the outbox needs multi-document transactions.

### 6.2 Conventions

| Concern | Convention |
|---|---|
| IDs | Time-ordered string IDs (UUIDv7) generated in the application; human order number `FS-YYMMDD-XXXXX` for support |
| Money | `Money { long minor; String currency }` (minor units); never floating point |
| Time | Always stored in UTC as `Instant` (BSON date). In DTOs, requests, responses and events every date-time is **UTC epoch seconds** (`long`, field names ending in `At`, for example `placedAt`). The only exception is a time of day that is not an instant, such as opening hours, stored as `HH:mm` plus the store's timezone |
| Translated text | `Map<String,String>` keyed by language tag, with fallback to the default language |
| Concurrency | `@Version` optimistic locking on aggregates |
| Geo | GeoJSON with `2dsphere` indexes (stores, zones) |
| Soft delete | `deletedAt` for business records; hard delete only for data-deletion requests |
| Audit | `createdAt`, `createdBy`, `updatedAt`, `updatedBy` on every document; back-office writes also go to `identity_audit_log` |
| PII | Phone and email encrypted with AES-GCM (key from a Kubernetes Secret, external KMS, or `.env` on a single server); a keyed HMAC blind index supports lookup |

### 6.3 Designed for sharding

The default install runs MongoDB unsharded, but every collection is designed so it can be sharded later without changing code.

Rules:
- Every collection has a documented shard key (in its Liquibase changelog and in the module's README), chosen so the hot queries include it and go to one shard.
- No unbounded arrays inside documents (use the bucket pattern or a child collection), and no document that grows forever.
- Unique indexes must start with the shard key, as MongoDB requires. Uniqueness that cannot include the shard key (for example email) lives in a small lookup collection keyed by that value.
- IDs are UUIDv7 (time-ordered), so any key on `_id` uses a **hashed** shard key to avoid a single hot shard.
- Transactions stay inside one module and touch as few documents as possible (aggregate plus outbox row).
- Small, rarely written collections (settings, zones, outbox, inbox with TTL) stay unsharded.
- **Replicas:** MongoDB always runs as a replica set, even with one member on a single server, so adding secondaries later is a config change with no downtime. The app reads from the primary by default; reads that tolerate slight staleness (reports, search rebuilds) use `secondaryPreferred`.
- **Adding shards later:** an existing replica set becomes the first shard of a sharded cluster online. The app then connects through `mongos` instead of the replica set (a connection-string change only), and each collection is sharded with the key in the table below.
- **Enforced, not just documented:** each repository declares its shard key, and every repository test runs against a **two-shard MongoDB** (Testcontainers) with every collection pre-sharded. Unique indexes without the shard key, missing shard keys on writes, and accidental scatter-gather on hot paths fail the build, long before production needs shards.

| Collection | Shard key | Why |
|---|---|---|
| `order_orders` | `{ _id: hashed }` | Lookups and updates by order ID are the hottest path; the live store board reads the small `order_active` collection |
| `order_active` | unsharded | Only orders in progress; removed on completion |
| `order_by_customer` | `{ customerId: hashed }` | Projection: order summaries for a customer's history |
| `order_by_store` | `{ storeId: 1, placedAt: 1 }` | Projection: order summaries for a store's history and reports; the compound key lets one very busy store's history split across shards instead of overloading one |
| `store_stores`, `store_menu_items` | `{ storeId: hashed }` | Menus are always read per store |
| `ledger_entries` | `{ accountId: 1, postedAt: 1 }` | Statements and balances are range reads per account |
| `payment_intents` | `{ orderId: hashed }` | Looked up by order and by webhook (stored with order ID) |
| `dispatch_jobs` | `{ _id: hashed }` | Updated by job ID |
| `rider_locations` | time-series, meta `riderId` | Append-only; time-series collections shard on the meta field |
| `review_reviews` | `{ storeId: hashed }` | Read per store |
| `notification_inbox` | `{ userId: hashed }` | In-app notifications read per user |
| `identity_users` | `{ _id: hashed }` | Read by user ID from the token |
| `identity_login_keys` | `{ loginKey: 1 }` (unique) | Email-hash to user ID lookup |
| `media_assets` | `{ _id: hashed }` | Read by media ID |

**One source of truth, plus read projections for other keys.** When a collection is read by a second key often enough that scatter-gather would hurt (orders by customer and by store), the module keeps a **projection**: a second collection sharded on that key, holding only the summary fields the screen needs (not a full copy). Rules:

- The main collection is the only source of truth; projections are never written by API calls directly.
- Projections are updated from the module's own outbox events by an idempotent consumer inside the same module (milliseconds behind, eventually consistent). Screens that must be exact (order detail, tracking) always read the main collection by ID.
- Every projection can be rebuilt from the main collection with one admin job, so a bug or a new field never means data loss.
- Shard keys are expensive to change later (MongoDB can reshard, but it is a heavy online operation), so each key is checked in the pre-release load test with skewed data (a few very busy stores and customers), not just uniform data.
- A projection is added only for a hot query on another key. Rare queries (admin search, exports) accept scatter-gather or use Elasticsearch.

### 6.4 Migrations (Liquibase)

- Each module ships `db/changelog/<module>/db.changelog-master.yaml` using the `liquibase-mongodb` change types (`createCollection`, `createIndex`, `insertMany`, `runCommand`).
- Liquibase tracking collections are per module: `<module>_databasechangelog` and `<module>_databasechangeloglock`, so migrations of one module never block another and travel with it when extracted.
- `platform-mongo` provides `ModuleLiquibaseRunner`, registered by each module's configuration class, so only `local` modules migrate.
- Production: migrations run once per install or upgrade before the application starts: a Helm `pre-install,pre-upgrade` hook Job on Kubernetes, `install.sh` / `upgrade.sh` on a single server (`foodstack-server --foodstack.migrate-only=true`). In migrate-only mode **every** module is `local` whatever the topology, and each uses its own datastore override, so all module databases are migrated in one step. Dev and tests: migrations run at startup.
- Migrations must be backward compatible with the previous release (expand, then contract in a later release) so rolling upgrades work.

## 7. Events (Kafka)

### 7.1 Topics and envelope

| Item | Convention |
|---|---|
| Topic | One per publishing module: `foodstack.<module>.events` (for example `foodstack.order.events`) |
| Key | Aggregate ID, so events of one order stay ordered |
| Partitions | 24 by default (configurable), set high from day one because adding partitions later re-maps keys and briefly breaks per-order ordering; replication factor 3 in production (1 on a single server) |
| Envelope | CloudEvents 1.0 JSON: `id`, `source` (`foodstack/<module>`), `type` (`foodstack.order.OrderPlaced.v1`), `time`, `subject` (aggregate ID), `traceparent`, `data` |
| Schema | Event payloads are records in `<module>-api/events`, versioned by type suffix; changes are additive, breaking changes get a new `vN` type |
| Consumer group | `foodstack-<consumingModule>`: every consuming module gets every event independently; inside a group each partition is processed by one consumer thread at a time, so a module's maximum parallelism per topic equals the partition count. The Helm chart sets listener concurrency so that replicas × concurrency ≤ partitions, and warns otherwise |
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

- Base path `/api/v1`; resources are plural nouns; JSON with camelCase; date-times as UTC epoch seconds (section 6.2).
- OpenAPI 3.1 generated by springdoc per module and merged into one document at `/api/v1/openapi.json`; the Angular API client is generated from it in CI.
- Errors: RFC 9457 `application/problem+json` with `code`, `traceId` and `errors[]` for validation, matching Module-Switch's wire format.
- Pagination: cursor-based (`?cursor=&limit=`), response `{ items, nextCursor }`.
- `Idempotency-Key` header required on order placement, payments and refunds; responses cached 24 h in Redis under `foodstack:<module>:idem:<key>`.
- `ETag` / `If-None-Match` on settings, branding and menus; `If-Match` for back-office edits to prevent lost updates.
- Rate limiting in two layers: **at the edge** (gateway, per IP and per client, before traffic reaches any pod; section 18.3) and **in the app** (per user and per action, Redis token bucket, stricter on login codes, order placement and coupon checks).
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
- The login UI is served by `identity` and themed from `settings` branding. It supports email one-time codes (stored hashed in Redis with 5 minute TTL, attempt limits), email and password (Argon2id), social login (OIDC federation), and TOTP for back-office users.
- Access tokens: JWT signed with ES256, 10 minute lifetime; refresh tokens rotated, 30 days for customers and 12 hours for back office. Signing keys come from a Kubernetes Secret (or `.env` on a single server) and are published through JWKS; rotation keeps the previous key valid.
- Token claims: `sub`, `roles`, `perms`, `storeIds` (store staff), `riderId`.
- **No per-request call to `identity`.** Every module is an OAuth2 resource server that validates the JWT itself, in memory: signature (ES256) against `identity`'s public keys, plus issuer, audience, expiry and not-before. The public keys (JWKS) are fetched once at startup and cached; a token signed with an unknown key ID triggers one refresh. Validation costs microseconds and keeps working even if `identity` is briefly down. `identity` is called only to log in, refresh a token, or fetch keys.
- Authorization uses method security with permissions such as `order:refund` and store-scoped checks (`storeIds` contains the target store), read from the token claims, so no lookup is needed.
- **Immediate revocation** (account blocked, password changed, role removed, logout everywhere): `identity` stores a per-user `tokenVersion` (also a token claim) and publishes `UserTokensRevoked`. Each module keeps revoked versions in Redis (`foodstack:identity:revoked:<userId>`) with a short in-memory cache, and rejects tokens whose version is older. Without this check a revoked token would still work until it expires (at most 10 minutes).
- **Edge check (Kubernetes):** an Istio `RequestAuthentication` + `AuthorizationPolicy` at the Gateway also validates the JWT for `/api/**`, so forged or expired tokens are rejected before reaching any pod. Public endpoints (menus, search, login) are listed explicitly.
- **Module-to-module, after a split**, three independent layers:
  1. *Who is the user:* Module-Switch forwards the caller's `Authorization` header, so the called module validates the same JWT locally and applies the same permissions. A module can never do more than the user who started the request.
  2. *Is the caller one of our modules:* the internal port 8081 requires `X-Module-Switch-Token` (a generated Secret) and is not routed by the Gateway, so it is unreachable from outside the cluster.
  3. *Is the network path trusted:* Istio ambient mTLS encrypts and authenticates every pod-to-pod connection with a workload identity, and `AuthorizationPolicy` allows port 8081 only from FoodStack server service accounts.
- Background work with no user (Kafka consumers, scheduled jobs) calls other modules with a short-lived service token from the `identity` client-credentials flow, carrying only the permissions that job needs. Events carry the acting user ID for audit, never a token.
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
| Rate limiting (app) | `foodstack:<module>:rl:<scope>:<id>` | token bucket; edge limits use `foodstack:gateway:rl:*` |
| Login codes | `foodstack:identity:otp:<emailHash>` | 5 min |
| Rider live location | `foodstack:rider:geo` (GEO set) | refreshed on each ping; stale riders pruned |
| Dispatch offer locks | `foodstack:dispatch:offer:<riderId>` | offer timeout |
| Outbox relay leader lock | `foodstack:<module>:outbox:leader` | 10 s, renewed |
| Real-time fan-out | `foodstack:<module>:rt:*` (Pub/Sub) | none |

Redis is a cache and coordination store only; losing it never loses business data. Production uses Redis with replicas (or a managed equivalent); Redis Cluster is supported because every key family has its own hash tag.

## 11. Search (Elasticsearch)

- Indices prefixed by module: `search_stores_v<N>` and `search_dishes_v<N>`, accessed through aliases `search_stores` and `search_dishes`; reindex by building a new version and swapping the alias.
- Built only from events (`StoreChanged`, `MenuChanged`, `StoreAvailabilityChanged`, `ReviewPublished`), never by reading another module's database; a full rebuild job replays from `store` through its API.
- Per-language fields with language analyzers (including Arabic and Urdu), `search_as_you_type` for autocomplete, `geo_point` for distance sorting, and a delivery shape per store (`geo_shape`): its zone polygons for self-delivery stores, or a circle of the operator's maximum delivery distance for operator-delivery stores, so search returns only stores that deliver to the customer's address.
- Ranking: open now, distance, rating, ETA, plus admin boosts.
- `SearchEngine` SPI in `search-core`; the Elasticsearch Java client is the default adapter.

## 12. Geo and maps

- `geo` owns store delivery zones (GeoJSON polygons, `geo_zones`), each belonging to one store with a fixed charge, minimum order and free-delivery threshold, and the **operator delivery pricing** (base + per km of road distance, maximum distance, optional peak multiplier, optional city override). `quote` uses the store's zones for self-delivery stores and the operator pricing for operator-delivery stores. `geo` also holds cities.
- The delivery mode is snapshotted on the order at placement, so the price the customer saw and the party paid always match. `ledger` credits the delivery charge to the store (self-delivery) or the operator (operator delivery), and posts operator rider pay as a percentage of the operator charge (optional minimum) plus tips. If support hands an operator-delivery order back to the store, the charge is re-credited to the store.
- Provider SPI: `GeocodingProvider`, `RoutingProvider`, `TileConfig`. Default adapter: Nominatim and OSRM (operator self-hosts or uses a hosted provider; public instances are only for demos). Optional Google Maps adapter.
- ETA = prep estimate + routing duration × city traffic factor (configurable); quotes are cached briefly.

## 13. Media storage (S3)

- The `media` module owns uploads. Other modules store only media IDs; `media` resolves them to URLs.
- Storage goes through an `ObjectStorage` SPI. The default adapter uses the AWS SDK v2 S3 client, so it works with AWS S3 and with S3-compatible stores (RustFS in the unit deployer, local development and tests, and other providers).
- Upload flow: the client asks `POST /api/v1/media/uploads` (type, size, purpose) → receives a short-lived presigned `PUT` URL for `<bucket>/<owningModule>/<entityId>/<uuid>` → uploads directly → calls `complete`. The server never streams large files through the JVM.
- Processing (on `complete`, on virtual threads): check magic bytes and size limits, strip EXIF/metadata, auto-rotate, resize to fixed variants (`thumb` 160 px, `card` 480 px, `detail` 1080 px), encode as WebP (quality about 80) and optionally AVIF, then delete the raw upload unless keeping originals is switched on. Processing is pure Java (ImageIO, metadata-extractor for EXIF orientation, webp-imageio for WebP, which bundles native libwebp), so it runs in plain `mvn test` and in the JRE image without system packages. AVIF and libvips are follow-ups if throughput needs them.
- Buckets: `public` (menu, store and branding images; served with `Cache-Control: public, max-age=31536000, immutable` and content-hashed names, so a CDN can sit in front) and `private` (rider documents; access only through 5-minute signed `GET` URLs).
- `media_assets` records owner module, entity, variants, sizes and reference count; a nightly job deletes unreferenced assets after a grace period.

## 14. Front-end design

### 14.1 Workspace

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

### 14.2 Patterns

- Standalone components, signals and zoneless change detection; NgRx SignalStore for feature state; lazy-loaded routes per area and feature.
- **Runtime branding:** on boot the app loads `/api/v1/settings/branding` and writes Material 3 design tokens as CSS custom properties (colour palettes generated from the operator's primary and secondary colours, typography, logo URLs). Components never hard-code colours.
- **Runtime i18n:** Transloco with translations fetched per language, because languages are chosen at runtime. `dir="rtl"` is set from the active locale; styles use logical properties.
- **Runtime config:** each front-end container serves `/config.json` built from environment variables at container start (API URL, OIDC issuer, maps tile URL), so one image works for every operator.
- Accessibility: Material components plus axe checks in CI; light and dark themes.
- Testing: unit tests with Vitest, end-to-end with Playwright against the unit deployer and the Kubernetes topologies (section 15.2).

### 14.3 Images

`ilyaslabs/foodstack-web` runs the SSR Node server; `ilyaslabs/foodstack-backoffice` is static assets on `nginx-unprivileged`. Both are non-root with read-only filesystems.

## 15. Build, CI/CD and releases

### 15.1 Backend build

- Parent POM inherits `spring-boot-starter-parent` 4.x, imports `module-switch-bom`, sets `java.version=25`, uses `${revision}` with the flatten plugin (as `ilyaslabs/cicd` `mvn-release.yml` passes `-Drevision=<tag>`).
- All `-api`, `-core`, `-impl`, `-web`, `platform-*` and `foodstack-bom` jars are published to Maven Central, so operators can build a custom server that adds or replaces modules.
- `foodstack-server` image: multi-stage Dockerfile, Spring Boot layered jar on a JRE 25 base, non-root, multi-arch (amd64, arm64), virtual threads on.

### 15.2 Workflows

| Workflow | Trigger | Does |
|---|---|---|
| `pr-checks.yml` | Pull request | Calls `ilyaslabs/cicd/.github/workflows/mvn-pr-checks.yml@main`; frontend lint, test and build; Helm lint and `kubeconform`; `ModuleRules.verify`; Trivy filesystem scan |
| `snapshot-release.yml` | Push to `main` | Calls `mvn-snapshot-release.yml`; pushes `:edge` images |
| `release.yml` | GitHub release `vX.Y.Z` | Calls `mvn-release.yml` (Maven Central); builds, signs and pushes images to Docker Hub and GHCR; builds `unit-deployer-<version>.zip` and attaches it to the GitHub release; packages the Helm chart and pushes it to GHCR as an OCI artifact |
| `e2e.yml` | Nightly and before release | Unit deployer install on a fresh VM-like runner (`install.sh`, `restart.sh`, `upgrade.sh`); kind cluster installs of the `monolith`, `split` and `microservices` topologies, Playwright suite, k6 smoke test |

A reusable `docker-release.yml` and `helm-release.yml` will be added to `ilyaslabs/cicd` so other ilyasLabs projects can share them.

### 15.3 Testing strategy

Full rules are in the [Testing Strategy](testing-strategy.md). In short:

| Level | Where | Rule |
|---|---|---|
| Lowest layer (repositories, outbox, Kafka, Redis, Elasticsearch, S3, provider adapters) | `<m>-core` | Runs against real infrastructure in Testcontainers (two-shard MongoDB) or WireMock for external providers; nothing mocked |
| Services, consumers, jobs | `<m>-core` | Class runs for real; the layer below and other modules' `-api` are mocked |
| Api implementation | `<m>-impl` | Runs for real; core services mocked |
| Controllers | `<m>-web` | `@WebMvcTest`; service mocked |
| Contracts | `<m>-api`, CI | Event JSON snapshots, `ModuleRules.verify`, ArchUnit layer rules, OpenAPI diff |
| Integration | `integration-tests` module only | A short list of business journeys through the public REST API, **each run twice: monolith and microservices** (one context per module) |
| Front-end | `frontend/` | Vitest components, Playwright journeys, visual and accessibility checks |
| Performance | `tools/k6` | Nightly smoke; full load before release |

## 16. Local development and seed data

- **One click:** `app/foodstack-server/src/test/java/.../LocalFoodstackApplication.java` starts the real application with starts reusable containers from `foodstack-platform-test` (two-shard MongoDB, Redis, Kafka, RustFS; Elasticsearch and Mailpit join when the modules that need them land) and runs `FoodstackApplication` with the `local` and `test-seed` profiles. Run it from the IDE with one click, or `mvn -pl app/foodstack-server spring-boot:test-run`. Docker is the only prerequisite.
- The Angular apps run with `pnpm start` and proxy `/api` to `localhost:8080`.
- **Seed data:** when the `test-seed` profile is active, each module seeds its own data through a `@Profile("test-seed")` seeder inside its own `-core` package, so only `local` modules seed and a module never writes another module's collections. Seeders are idempotent (a `<module>_seed` marker document) and run in dependency order (identity, settings, geo, media, store, rider, promotion, then sample orders through the public APIs).
- Seed content: an admin, a finance user and support user, two cities, about 10 stores with menus, photos, opening hours and their own zones, store riders and operator riders, customers with addresses, coupons, and orders in every state. Logins are printed once in the startup log.
- Safety: startup fails if `test-seed` is active together with `foodstack.environment=production`.

## 17. Single-server deployment: unit deployer

The default way to launch. The whole platform runs on one server with Docker, as a modular monolith.

### 17.1 Package

Each release attaches `unit-deployer-<version>.zip`, built by a Maven assembly in `deploy/unit-deployer/`:

```
unit-deployer-<version>/
├─ foodstack-server.jar            the application (all modules local)
├─ web/  backoffice/               built Angular apps (SSR server bundle and static files)
├─ docker-compose.yml
├─ .env.example                    every setting, documented
├─ config/                         application.yaml, Caddyfile, MongoDB replica-set init, Kafka and Elasticsearch settings
├─ install.sh  start.sh  stop.sh  restart.sh
├─ status.sh  logs.sh  backup.sh  restore.sh  upgrade.sh
└─ README.md
```

### 17.2 Compose stack

All services share one Docker network, `foodstack`. Only the reverse proxy publishes ports (80 and 443).

| Service | Image | Notes |
|---|---|---|
| `proxy` | Caddy | The API gateway on a single server: automatic HTTPS for the operator's domains, routing of `/api`, `/oauth2`, `/login` to the server and the rest to the web apps, per-IP rate limiting, request-size limits and security headers |
| `server` | Eclipse Temurin 25 JRE | Runs the mounted `foodstack-server.jar`; healthcheck on actuator readiness |
| `web` | Node (LTS) | Customer app SSR server from the mounted bundle |
| `backoffice` | nginx-unprivileged | Static back-office app |
| `mongodb` | MongoDB | Single-node replica set (needed for transactions), auth on |
| `kafka` | Apache Kafka | Single node, KRaft mode |
| `redis` | Redis | Password, AOF persistence |
| `elasticsearch` | Elasticsearch | Single node, security on, heap sized from `.env` |
| `storage` | RustFS | S3-compatible storage for media (MinIO no longer publishes images) |
| `nominatim`, `osrm` (optional) | Nominatim, OSRM | Self-hosted address search and routing for the operator's region; enabled with a Compose profile when not using a hosted maps provider |

Every service has a healthcheck; `server` starts only when its dependencies are healthy. All data and logs are bind-mounted under the install directory, and every service uses `restart: unless-stopped` with log rotation.

### 17.3 Scripts

| Script | Does |
|---|---|
| `install.sh <install-dir> [--domain example.com]` | Checks Docker and Compose v2, CPU, RAM, disk and ports; sets `vm.max_map_count` for Elasticsearch; creates `<dir>/{data/{mongodb,kafka,redis,elasticsearch,storage,caddy},logs,config,backups}`; generates strong secrets, JWT signing keys and the PII key into `<dir>/.env` (mode 600); copies the jar, apps and config; runs migrations once (`--foodstack.migrate-only=true`); starts the stack; optionally installs a systemd unit to start on boot; prints the first administrator sign-in |
| `start.sh` / `stop.sh` | `docker compose up -d` / `down` (data is kept) |
| `restart.sh [service]` | Restarts everything or one service |
| `status.sh` / `logs.sh [service]` | Health summary / follow logs |
| `backup.sh` | `mongodump`, a copy of the media storage and config into `<dir>/backups/<timestamp>` |
| `restore.sh <backup-dir>` | Stop the application, restore MongoDB and media, rebuild search, start |
| `upgrade.sh <new-zip>` / `--rollback` | Backup, replace jar, apps and compose file, run migrations, restart / restore the previous version |

Step-by-step operator instructions are in the [Deployment Guide](deployment-guide.md).

Moving to Kubernetes later is a data migration (dump and restore) plus configuration; no code changes.

## 18. Kubernetes and Helm

Optional. Use it when one server is no longer enough; at launch the unit deployer (section 17) runs the whole modular monolith on one server.

### 18.1 Chart layout

```
k8s/helm/foodstack/
├─ Chart.yaml
├─ values.yaml            production defaults: monolith, external datastores, Istio ambient
├─ values-split.yaml      example: topology=split with sizing
├─ values-microservices.yaml example: topology=microservices with sizing
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

### 18.2 Module topology: one setting, any shape

The operator picks a topology with **one value**. Everything else (Deployments, Services, Module-Switch modes and URLs, internal tokens, routes, autoscaling, mesh policies) is generated by the chart. Every topology uses the same image.

```yaml
topology: monolith          # monolith | split | microservices | custom
```

| Preset | Deployments | When |
|---|---|---|
| `monolith` (default) | `core` runs every module | Launch, and as long as one Deployment with several replicas is enough |
| `split` | `core` plus `order`, `dispatch`, `search` on their own | The busiest modules need independent scaling |
| `microservices` | One Deployment per module (14) | Large scale, separate teams, or per-module datastores |
| `custom` | Whatever `server.deployments` lists | Any grouping in between |

```yaml
# custom example: group any modules, size each group independently
topology: custom
server:
  deployments:
    core:     { modules: [identity, settings, geo, media, store, cart, promotion, payment, rider, review, notification, ledger] }
    order:    { modules: [order],    replicas: 3, autoscaling: { min: 3, max: 20 } }
    dispatch: { modules: [dispatch], replicas: 2, autoscaling: { min: 2, max: 10 } }
    search:   { modules: [search],   replicas: 2 }
  moduleDefaults:                     # optional per-module datastore overrides when a module is extracted
    order: { mongodbUriSecret: order-mongodb }
```

For each Deployment the chart:

1. Sets its modules to `local` and exposes them on the internal port 8081.
2. Sets every module owned by another Deployment to `http` with `url: http://foodstack-<deployment>.<namespace>.svc.cluster.local:8081`, and the rest to `off`.
3. Generates the internal Module-Switch token (Secret) and the Istio `AuthorizationPolicy` allowing only FoodStack server service accounts on port 8081.
4. Generates `HTTPRoute` rules sending each module's public REST prefixes to that Deployment's Service.
5. Creates a `HorizontalPodAutoscaler`, a `PodDisruptionBudget` and topology spread for that Deployment.
6. Fails `helm install` with a clear message if a module is listed twice, a listed module is unknown, or a module needed by another is not run anywhere (the same checks Module-Switch runs at startup).

**Moving between topologies** is `helm upgrade --set topology=split` (or a new `custom` list). Pods are rolled one Deployment at a time; Module-Switch retries calls that never ran while a target is starting, so traffic keeps flowing. Data does not move: modules keep their collections, so a split is a pure deployment change. Moving a module to its own database is a separate, optional step (copy the whole `foodstack_<module>` database to the new cluster, then set its datastore override).

**Load balancing** happens at two layers, both automatic:

- **Public traffic:** Gateway → Service of the Deployment that owns the path → its pods. In ambient mode, the namespace waypoint balances HTTP requests per request (least-request), not per connection, so long-lived keep-alive connections never pin traffic to one pod.
- **Module-to-module traffic:** Module-Switch calls the Service DNS name; ztunnel/waypoint spreads requests across that Deployment's pods with mTLS. More replicas, or autoscaling, are picked up with no configuration change.
- Without Istio (`mesh.enabled=false`) both still work through the Kubernetes Service (connection-level balancing); the chart then lowers HTTP keep-alive time on the Module-Switch client so connections rebalance.

**CI proof:** the `e2e.yml` workflow installs each preset (`monolith`, `split`, `microservices`) on a kind cluster and runs the same Playwright and API suites, so every topology is tested on every release.

### 18.3 Networking, mesh and load balancing

- Services: `foodstack-<deployment>` (ports `http` 8080 public API, `internal` 8081 Module-Switch), `foodstack-web`, `foodstack-backoffice`. Cluster-local DNS names such as `foodstack-order.foodstack.svc.cluster.local`.
- Istio ambient: the namespace is labelled `istio.io/dataplane-mode=ambient`; ztunnel gives mTLS and L4 authorization with no sidecars. A waypoint proxy for the namespace gives L7 load balancing (least request), retries for idempotent routes and per-route timeouts.
- `AuthorizationPolicy`: port 8081 only from FoodStack server service accounts; public port only from the Gateway; datastores only from the server.
- **API gateway:** Gateway API `Gateway` (Istio implementation, Envoy) with hosts for `web`, `backoffice` and `api`, TLS from cert-manager; HTTP→HTTPS redirect; HSTS. It does path routing to module Deployments, JWT validation (`RequestAuthentication`), CORS, request-size limits, per-route timeouts and retries for idempotent routes. The gateway Deployment autoscales on its own HPA.
- **Global edge rate limiting:** the chart deploys Envoy's rate-limit service (2+ replicas, backed by the same Redis, keys `foodstack:gateway:rl:*`) and attaches it to the Gateway. Default limits are per client IP and per OAuth client, with tighter limits on `/login`, `/oauth2/token` and order placement; all limits are values in `gateway.rateLimits`. Exceeding a limit returns `429` with `Retry-After`. If the rate-limit service is unreachable, the gateway fails open so an outage there never takes the platform down; the app-level limits still apply.
- Without Istio (`mesh.enabled=false`) the chart still works, using any Gateway API implementation and NetworkPolicies, but without mTLS.

### 18.4 Reliability and scaling

- Readiness and liveness from Spring Boot actuator groups; startup probe for slow starts; graceful shutdown (30 s) drains SSE connections.
- HPA on CPU and on request rate (Prometheus adapter, optional). PDB `minAvailable: 1` (2 for `order` in split). Topology spread across zones and nodes.
- Resource requests and limits set per Deployment; JVM sized with `MaxRAMPercentage`.
- Datastores in production: managed or operator-run MongoDB replica set, Redis with replicas, Kafka with 3 brokers, Elasticsearch with 3 nodes. Connection details come from Secrets referenced in values.

### 18.5 Observability

- Spring Boot with Micrometer and the OpenTelemetry exporter: traces (W3C context through HTTP, Module-Switch calls and Kafka headers), metrics (including `module.switch.call`, outbox lag, consumer lag, dispatch timings, order funnel), structured JSON logs with `traceId`.
- `foodstack-observability` chart (optional): OpenTelemetry Collector, Prometheus, Grafana with FoodStack dashboards (golden signals per module, order funnel, dispatch, Kafka lag), Tempo, Loki, and alert rules (error rate, latency, outbox lag, DLT growth, certificate expiry).

## 19. Configuration reference (excerpt)

```yaml
foodstack:
  mongodb:
    uri: ${MONGODB_URI}                     # cluster shared by default; database is foodstack_<module>
    database-prefix: foodstack_
  modules:
    order:
      mongodb.uri: ${ORDER_MONGODB_URI:}    # optional: dedicated cluster for this module's database
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP}
    topic-partitions: 24
  redis:
    url: ${REDIS_URL}
  search:
    engine: elasticsearch
    url: ${ELASTICSEARCH_URL}
  security:
    issuer: https://api.example.com
    pii-key: ${PII_KEY}                     # from Secret
  storage:
    endpoint: ${S3_ENDPOINT:}               # empty = AWS S3; RustFS URL on one server
    public-bucket: foodstack-public
    private-bucket: foodstack-private
  migrate-only: false
  environment: production                  # test-seed profile refuses to run here
module-switch:                              # generated by Helm per Deployment
  modules: { … }
  expose: { port: 8081, modules: [ … ], token: ${INTERNAL_TOKEN} }
  propagate-headers: [Authorization, Accept-Language]
```

## 20. Risks and open technical questions

| # | Item | Proposal |
|---|---|---|
| T1 | Kafka adds operational weight for small pilots | The unit deployer and `values-dev.yaml` run single-node KRaft with small heap; docs list managed Kafka options; outbox keeps the app working while Kafka is briefly down |
| T2 | One-time-code login on Spring Authorization Server | Implement email codes (and phone codes once SMS exists) as a step in the hosted login page, not a custom grant, keeping standard Authorization Code + PKCE for all clients |
| T3 | Choice of first card gateway (hosted payment page support required) | **Decided:** one global gateway with a hosted payment page first; regional adapters later through the same SPI |
| T4 | Public OSM services have strict usage policies | The Helm chart and the unit deployer ship optional self-hosted Nominatim and OSRM services (off by default, enabled per region); the deployment guide explains the data import |
| T5 | Licences of third-party servers (Liquibase FSL, Elasticsearch) | Document in `docs/licensing.md`; operators run unmodified upstream software |
| T6 | Module-Switch is new (0.x) | FoodStack pins a released version and contributes fixes upstream; split-topology tests run in CI |

## 21. M0 implementation notes

What M0 built differs from the design above in these places. Each is deliberate; the follow-up column says when it changes.

| Area | Design | M0 | Follow-up |
|---|---|---|---|
| Object storage | MinIO | RustFS (Apache-2.0, S3-compatible) in the unit deployer, local runs and tests, because MinIO stopped publishing images. The code only uses the S3 API, so AWS S3 and other providers work unchanged | None needed |
| Image processing | libvips | Pure Java with webp-imageio (section 13) | Add libvips or AVIF only if throughput needs it |
| Web tokens | Refresh-token rotation, access token in memory | Public SPA clients get no refresh token from Spring Authorization Server. The apps keep the access token in `sessionStorage` (tab-scoped) and, when it expires, send the user through `/oauth2/authorize` again, which signs them straight back in while the identity session lasts. Sign-out uses OIDC RP-initiated logout (`/connect/logout`) | Refresh tokens through a backend-for-frontend, or in-memory tokens with silent renew in an iframe |
| Identity session | Not specified | The login session lives in the server's memory, so it ends on restart and is not shared between replicas | Spring Session on Redis before running identity with more than one replica |
| Login methods | Email codes, password, social, TOTP | Email and password (Argon2id) only. Email codes need the notification module and SMTP | M2 (notifications), M5 (TOTP, social) |
| First admin | One-time link to set the password | `install.sh` generates the password, stores it in `.env` and prints it once | Password change and reset with the notification module |
| Elasticsearch | Always in the Compose stack | Compose profile `search`, off by default; no module uses it yet | Switched on by default with the search module (M1) |
| OpenAPI and API client | springdoc per module, generated Angular client | Hand-written typed clients in `frontend/projects/api-client` | Add springdoc and generate the client in CI |
| Rate limiting | Edge plus per-user limits in Redis | Only Caddy request-size limits on one server | Per-IP and per-user limits with the first public write endpoints beyond sign-up |
| Libraries | `platform-core`, `-mongo`, `-kafka`, `-security`, `-test` | Also `platform-web` (problem+json errors, ETags), shared by every `-web` module | None |
| Module dependencies | | `settings` calls `media` through `MediaApi` (claim and release the logo), the first synchronous module-to-module call; it runs over HTTP in the microservices topology | None |
| Authorization documents | | `identity_authorizations` is not sharded (short-lived, small) | Shard by `_id` hash if volume calls for it |

