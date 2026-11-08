# FoodStack: Technical Design Document (TDD)

| | |
|---|---|
| Status | Draft v1, agreed in grooming with ilyas (2026-10-27, last updated 2026-10-29) |
| Owner | ilyas (decisions), Claude (architecture draft) |
| Related | [BRD](BRD.md), [PRD](PRD.md), [Module-Switch](https://github.com/ilyaslabs/module-switch), [Module-Switch example](https://github.com/ilyaslabs/module-switch-example) |
| How it works today | [Architecture and flow diagrams](architecture/README.md): what the built code actually does, with sequence diagrams per action and the list of differences from this design |

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
| D9 | Identity | Own `identity` module with a **stateless auth API** (revised 2026-11-03, section 9.1): email and password, email one-time code, social login (M5), JWT access tokens and rotating refresh tokens. Spring Authorization Server remains only for the public keys (JWKS) and the service token. No SMS in v1, so phone OTP comes later |
| D10 | Real time | Server-Sent Events for web, FCM push for devices |
| D11 | Maps | Provider SPI; default OpenStreetMap stack (MapLibre, Nominatim, OSRM); Google Maps adapter |
| D12 | Front-end | Angular workspace, two apps (`web`, `backoffice`), Angular Material 3 with design tokens, no micro-frontends |
| D13 | Repository | Monorepo: backend, frontend, `k8s/helm`, docs |
| D14 | Artifacts | Maven `io.github.ilyaslabs:foodstack-*`; images `ilyaslabs/foodstack-server`, `ilyaslabs/foodstack-web`, and `ilyaslabs/foodstack-backoffice` on Docker Hub, mirrored to GHCR |
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
| D25 | Scale-out | Scaling is adding machines only, never a code change; the same code runs on one box and across many (section 7.5). One deployment serves one country |

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
| `store` | Brands and their branches (stores), delivery mode (self or operator), opening hours, temporary-close switch, brand menus, items, modifiers, per-branch price and availability overrides, commission config, QR menu codes (section 5.7) | `/api/v1/brands`, `/api/v1/stores`, `/api/v1/price-changes` | `geo`, `settings` | `BrandChanged`, `StoreChanged`, `MenuChanged`, `ItemAvailabilityChanged`, `StoreAvailabilityChanged`, `StorePaymentCollectionChanged` | none |
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
payment_merchant_accounts { id, owner: OPERATOR | BRAND | STORE, brandId?, storeId?, gateway, credentialsRef (encrypted),
                            status: PENDING_VERIFICATION | ACTIVE | SUSPENDED | REVOKED }
store_stores.paymentCollection { mode: OPERATOR | DIRECT, allowedBy, allowedAt, creditLimit }
```

- `settings` holds the global switch (`payments.directCollection.enabled`, default `false`) and the default credit limit.
- `store` owns the per-store permission (`mode`), changed only through an admin endpoint guarded by `store:payment-collection:manage`; changes emit `StorePaymentCollectionChanged`.
- `payment` owns the store's merchant account. Credentials are encrypted with the PII key (or held in an external secret store through the gateway SPI) and verified by a test call before becoming `ACTIVE`.
- At order placement, `order` asks `payment.resolveMerchant(storeId)`. It looks for the branch's own account first, then its brand's account (section 5.7), and returns it only when the global switch is on, the branch's mode is `DIRECT`, the account is `ACTIVE` and the store is not over its credit limit; otherwise it returns the operator's account. The chosen account is snapshotted on the order, so refunds always go to the same account.
- `ledger` posts different entries depending on the snapshot. Operator-collected: cash in to the operator, payable to the store for its share. Direct-collected: cash in to the store, **receivable from the store** for commission, the delivery charge when an operator rider delivered, service fee and rider tips. Settlement nets payables and receivables per store per period and reports who pays whom.
- `ledger` tracks each store's outstanding receivable. When it passes the credit limit, it emits `StoreCreditLimitExceeded`; `payment` marks the store account `SUSPENDED` (collection falls back to the operator) and `notification` alerts the store and finance admins. `StoreBalanceSettled` reverses it.
- Webhooks from a store's account are routed by account ID (`/api/v1/payment-webhooks/{accountId}`) and verified with that account's signing secret.
- Later option: gateway-native split payments, adding a `SPLIT` mode to the same model.
- Card payments can be switched off (`payments.card.enabled`). Integration style per gateway adapter: `HOSTED_PAGE` (redirect to the gateway's hosted payment page; default, keeps card data entirely off our pages) or `EMBEDDED` (gateway fields in our checkout). Saved cards (`payments.savedCards.enabled`) are off by default; when on, only gateway tokens are stored.

### 5.5 Promotions: who creates them and where they apply

- `promotion_promotions { owner: OPERATOR | STORE, storeIds, cityIds, funding: {operatorPct, storePct}, paymentMethods: [CASH, CARD]?, merchantAccount: ANY | OPERATOR_ONLY }`.
- Store owners create promotions for their own brand's branches only (all or chosen ones, stored as `storeIds`), always store-funded. A branch manager can do so for their branch only when the brand owner allows it.
- Operator promotions can be limited to payment methods and to `OPERATOR_ONLY`, meaning they apply only when the payment is collected on the operator's merchant account. `order` passes the payment method and resolved merchant account in the validation context, so the check happens at placement.

### 5.6 Dispatch algorithm (v1)

For `deliveryMode: STORE`, the job goes straight to the chosen store rider (or stays with the store). The steps below apply to operator riders:

1. On `OrderAccepted` with `deliveryMode: OPERATOR`, create a job with target pickup time = accepted time + prep estimate − travel time to store.
2. Candidate riders: online, in the store's city, within radius R (default 5 km) of the store, from Redis GEO (`GEOSEARCH`).
3. Score = w1·distance-to-store + w2·active-jobs + w3·(1 − acceptance-rate) + w4·idle-time bonus. Weights in settings.
4. Offer to the best rider; lock the rider in Redis (`SET NX PX`) for the offer timeout (30 s). On decline or timeout, move to the next; widen radius after N failures; alert admins after the configured limit.
5. All transitions are persisted and emitted as events; the admin live map subscribes over SSE.

### 5.7 Brands and branches

Every store is a **branch** of a **brand** (PRD 4.2.1). The data model ships in M1, so single restaurants and chains use the same code from day one; the multi-branch screens come in M4. In code and APIs a branch keeps the name *store* (`storeId`); only the UI says Brand and Branch.

```
store_brands        { _id, name, description, logoMediaId, coverMediaId, cuisines, ownerUserIds,
                      commission { percent, fixed }, version }
store_stores        { _id, brandId, branchName, ownerUserIds, address, location, openingHours, closures,
                      temporarilyClosed, prepMinutes, deliveryMode, pickupEnabled,
                      commissionOverride?, paymentCollection, version }
store_menu_items    { _id, brandId, sectionId, name, price, modifiers, branchIds?, version }
store_item_overrides{ _id, storeId, itemId, modifierOptionId?, priceOverride?, soldOut, approval?, version }
```

- **Single restaurant:** onboarding creates a brand with the same name and one branch. The brand is hidden in the UI while it has one branch, so nothing changes for single restaurants.
- **What lives where:** name, images, cuisines, the menu, default prices and default commission on the brand; address, hours, closures, delivery mode and zones, pickup, QR codes, riders and payouts on the branch. Orders, delivery zones, dispatch jobs, reviews and ledger accounts all stay keyed by `storeId`, so the order, geo, dispatch and ledger designs above are unchanged.
- **Menu:** one menu per brand. `branchIds` limits a branch-only item to chosen branches (absent means all). A branch's effective menu is the brand menu filtered by `branchIds` and merged with that branch's overrides (price, sold out). It is built in `store-core` and cached in Redis per branch (`foodstack:store:menu:<storeId>`), evicted on `MenuChanged` for the brand or `ItemAvailabilityChanged` for the branch. Price overrides follow the same approval rule as other store price changes.
- **Franchises:** `store_stores.ownerUserIds` can differ from the brand's owners. Payouts and bank details are per branch, so a franchisee is paid directly.
- **Commission:** the branch's `commissionOverride` if set, else the brand's `commission`. `ledger` reads the resolved value from the order snapshot, as today.
- **Merchant accounts:** `payment.resolveMerchant(storeId)` checks the branch's own account, then the brand's, then falls back to the operator (section 5.4).
- **Riders:** store riders belong to the brand and can be assigned to jobs of any of its branches; each job still has one `storeId`.
- **Reports:** ledger balances and payouts stay per branch; the brand roll-up sums the branches' accounts by `brandId` (copied onto ledger accounts at creation).
- **Search:** one document per branch, with the brand fields copied in, collapsed on `brandId` at query time (Elasticsearch `collapse`), so a brand shows once: the nearest branch that delivers to the address. `BrandChanged` reindexes all of the brand's branches.
- **Access:** see section 9.1 (`brandIds` claim).

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
- Small, rarely written collections (settings, zones) stay unsharded. Collections written on every request do not, however short-lived their rows: the outbox (`{ bucket: 1, _id: 1 }`), the consumer inbox (`{ _id: hashed }`) and `order_active` (`{ storeId: hashed }`) are sharded so no single shard takes every write (section 7.5).
- **Replicas:** MongoDB always runs as a replica set, even with one member on a single server, so adding secondaries later is a config change with no downtime. The app reads from the primary by default; reads that tolerate slight staleness (reports, search rebuilds) use `secondaryPreferred`.
- **Adding shards later:** an existing replica set becomes the first shard of a sharded cluster online. The app then connects through `mongos` instead of the replica set (a connection-string change only), and each collection is sharded with the key in the table below.
- **Enforced, not just documented:** each repository declares its shard key, and every repository test runs against a **two-shard MongoDB** (Testcontainers) with every collection pre-sharded. Unique indexes without the shard key, missing shard keys on writes, and accidental scatter-gather on hot paths fail the build, long before production needs shards.

| Collection | Shard key | Why |
|---|---|---|
| `order_orders` | `{ _id: hashed }` | Lookups and updates by order ID are the hottest path; the live store board reads the small `order_active` collection |
| `order_active` | `{ storeId: hashed }` | Only orders in progress, removed on completion, but written on every order step; the store board reads one branch, so its query goes to one shard |
| `<module>_outbox` | `{ bucket: 1, _id: 1 }` | One insert per event; `bucket` (0 to 63) spreads writes over every shard and lets each relay replica read only its buckets |
| `<module>_inbox` | `{ _id: hashed }` | One insert per consumed event (dedupe by event id) |
| `order_by_customer` | `{ customerId: hashed }` | Projection: order summaries for a customer's history |
| `order_by_store` | `{ storeId: 1, placedAt: 1 }` | Projection: order summaries for a store's history and reports; the compound key lets one very busy store's history split across shards instead of overloading one |
| `store_brands` | `{ _id: hashed }` | Read by brand ID |
| `store_stores` | `{ _id: hashed }` | A branch is read by its ID |
| `store_menu_items` | `{ brandId: hashed }` | Menus are read per brand and shared by its branches |
| `store_item_overrides` | `{ storeId: hashed }` | A branch's price and sold-out overrides are read together when its menu is built |
| `ledger_entries` | `{ accountId: 1, postedAt: 1 }` | Statements and balances are range reads per account |
| `payment_intents` | `{ orderId: hashed }` | Looked up by order and by webhook (stored with order ID) |
| `dispatch_jobs` | `{ _id: hashed }` | Updated by job ID |
| `rider_locations` | time-series, meta `riderId` | Append-only; time-series collections shard on the meta field |
| `review_reviews` | `{ storeId: hashed }` | Read per store |
| `notification_inbox` | `{ userId: hashed }` | In-app notifications read per user |
| `identity_users` | `{ _id: hashed }` | Read by user ID from the token |
| `identity_refresh_tokens` | `{ _id: hashed }` | Looked up by the token's SHA-256; a sign-in's tokens share `familyId` |
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
| Consumer group | `foodstack-<consumingModule>`: every consuming module gets every event independently; inside a group each partition is processed by one consumer thread at a time, so a module's maximum parallelism per topic equals the partition count. Listener threads per module come from `foodstack.kafka.concurrency.<module>` (default `foodstack.kafka.listener-concurrency`, 3); the Helm chart sets them so that replicas × concurrency ≤ partitions, and warns otherwise |
| Retry | Spring Kafka retry with backoff (3 attempts), then `foodstack.<module>.events.dlt`; admin UI lists DLT messages for replay |
| Retention | 7 days (topic), DLT 30 days |

### 7.2 Transactional outbox and idempotent inbox

```
[ module transaction ]  write aggregate  +  insert <module>_outbox {eventId, key, bucket, traceparent, payload, status=NEW}
[ relay ]               every replica polls NEW rows of the work buckets it holds every 100 ms, batch 500,
                        sends with idempotent producer (acks=all), marks SENT; TTL index removes SENT after 7 days
[ consumer ]            per message: if eventId in <consumer>_inbox → skip; else handle + insert inbox in one transaction
```

This gives at-least-once delivery with effectively-once processing, and no event is lost if Kafka is briefly unavailable. Events go through Kafka in every topology, including the monolith, so behaviour never changes when a module is split out.

### 7.3 Work buckets: background work on every replica

Background work that must not run twice at once (the outbox relay, the dispatch matcher, the accept-timeout sweep) is split into **64 fixed buckets** instead of running on one elected leader (`platform-kafka` `BucketLeases`, `PartitionedTask`). A record's bucket is `crc32(key) % 64`, stored on the record and indexed: the aggregate id for the outbox (so one aggregate's events keep their order), the branch for dispatch jobs, the order for the sweep. Each replica heartbeats into `<ns>:workers:<work>`, takes up to `ceil(64 / live replicas)` per-bucket leases (`<ns>:workers:<work>:bucket:<n>`) and gives back what is over its share when a replica joins, like a Kafka consumer group. A replica that dies stops renewing; its leases lapse and the others pick them up. One replica alone holds all 64, so a single server behaves as before. Every Redis command touches one key, so it runs on Redis Cluster. The daily search re-read stays a single round per interval (`SET NX`), because it is one job, not a stream of work.

### 7.4 Tracing through events

The server traces with OpenTelemetry (Micrometer bridge, `spring-boot-starter-opentelemetry`): HTTP requests, Module-Switch calls (sender and receiver observations) and Kafka listeners. `OutboxWriter` stores the W3C `traceparent` of the request that wrote an event; the relay sends it as the Kafka `traceparent` header and in the CloudEvents envelope; listeners run with observation on (`spring.kafka.listener.observation-enabled`) and continue the trace. Spans are exported over OTLP only when `FOODSTACK_TRACING_EXPORT=true` (endpoint `FOODSTACK_OTLP_ENDPOINT`, sampling `FOODSTACK_TRACING_SAMPLING`, default 10%); metrics are not pushed (`management.otlp.metrics.export.enabled=false`).

### 7.5 Scale-out rules (scale review, 2026-11-03/04)

The goal (ilyas): grow by adding machines, never by changing code, while one box keeps running the same code. Each rule below came from a review of one user flow at national scale and is built (PR #7). Ids are the review's gap numbers.

| Gap | Hot spot | Rule | Where |
|---|---|---|---|
| 1, 1b | Sign-in kept a server session and a redirect through the authorization server; the revocation cache was unbounded | Stateless auth API with rotating refresh tokens in MongoDB (section 9.1); the per-JVM revocation cache is a bounded Caffeine cache | `identity`, `platform-security` |
| 2, 5, 6 | One leader per module relayed the outbox, matched riders and swept timeouts | Work buckets on every replica (7.3) | `platform-kafka`, `dispatch`, `order` |
| 3, 4, 14 | One GEO set and one live channel for every rider in the country; plain pub/sub reaches every Redis Cluster node | Riders in one GEO set per 0.25° map cell (`<ns>:rider:geo:<cell>`); live channels per cell (`rt:cell:<cell>`), maps subscribe to the cells they show (at most 64, wider views must zoom in); sharded pub/sub on a cluster; Redis standalone, Sentinel or Cluster by config only | `rider`, `dispatch`, `platform-kafka` `LiveChannels`, `fs-map` |
| 7 | Every quote read zones and operator pricing from MongoDB | Redis caches with generation keys, 10 min, raised after each change and by `geo`'s own event listener | `geo` `GeoCache` |
| 8 | No edge limits on one server; no traces across Kafka | On Kubernetes, the Envoy rate-limit service (M5 chart); one server keeps only the app's own limits (ilyas, 2026-11-07); tracing as in 7.4 | `deploy`, `platform-mongo`, `platform-kafka` |
| 9 | Search: one shard setting, deep pages, a 10,000-hit brand lookup, unordered writes | Shards and `auto_expand_replicas` from config (applied to an existing index at start-up); results stop at 500 (`foodstack.search.max-results`); brand lookups page through a point in time with `search_after`; writes carry the branch version as an external version, so an older re-read arriving late is refused; listener threads per module from config | `search` |
| 10 | Self-delivery zones have no size cap, so a zone alone could flood a search | Discovery radius on top of the zone: 8 km road by default, widened by the customer in steps (8, 15, 25 km; `foodstack.search.discovery-steps-km`); a direct link and checkout use the zone only | `search`, web home page |
| 11 | Hours, peak windows and tickets used the deployment's time zone | Each branch has its own IANA zone, found offline from its location (IANA `zone.tab` plus timeshape boundaries, only for countries with several zones) and editable; hours, open-now, closures, peak windows and order times use it | `store`, `geo`, back office |
| 12a, 12d | Every branch page view reached the app; in split deployments each view also called `settings` | `Cache-Control: public, s-maxage=30` with ETags on live branch pages and menus (a CDN or the edge may serve them); services that call `settings` over the network keep its answers 30 s | `store-web`, server `RemoteSettingsCache` |
| 13a, 16 | Outbox, inbox and `order_active` were unsharded write hot spots | Sharded as in 6.3 | `platform-mongo`, `order` |
| 13b | A nationwide unlimited coupon made one promotion document the hottest write | Only limited coupons count uses on the promotion; transactions retry `TransientTransactionError` (up to four attempts) | `promotion`, `platform-mongo` |
| 15 | Kafka consumers sent emails and push inline, so a slow mail server stalled a partition | Consumers only write the inbox and queue deliveries; bounded senders and the periodic sender on every replica send them | `notification` |

Accepted as they are: order placement is a distributed transaction across shards; the admin all-orders list scatter-gathers (low traffic); a brand menu edit fans out one re-index per branch through Kafka. For the ledger (M4): an append-only ledger sharded by store, and reports from daily roll-ups, never live aggregation over orders.

## 8. API design

### 8.1 Conventions

- Base path `/api/v1`; resources are plural nouns; JSON with camelCase; date-times as UTC epoch seconds (section 6.2).
- OpenAPI 3.1 generated by springdoc per module and merged into one document at `/api/v1/openapi.json`; the Angular API client is generated from it in CI.
- Errors: RFC 9457 `application/problem+json` with `code`, `traceId` and `errors[]` for validation, matching Module-Switch's wire format.
- Pagination: cursor-based (`?cursor=&limit=`), response `{ items, nextCursor }`.
- `Idempotency-Key` header required on order placement, payments and refunds; responses cached 24 h in Redis under `foodstack:<module>:idem:<key>`.
- `ETag` / `If-None-Match` on settings, branding and menus; `If-Match` for back-office edits to prevent lost updates.
- Rate limiting in two layers: **at the edge** on Kubernetes (per client IP, before traffic reaches any pod: the Envoy rate-limit service, section 18.3; a single server has no edge limits, ilyas 2026-11-07) and **in the app** (per user and per action, a fixed window in Redis, stricter on sign-in, login codes, order placement and coupon checks).
- Additive changes only within `v1`; deprecations announced with a `Deprecation` header.

### 8.2 Routing in split deployments

Every REST prefix belongs to exactly one module (section 5.2). The Helm chart generates Gateway API `HTTPRoute` rules from that map, sending each prefix to the Deployment that runs the module locally. In the monolith all prefixes go to one Service.

### 8.3 Real-time

- `GET /api/v1/orders/{id}/stream` and `GET /api/v1/dispatch/live` (admin) are SSE endpoints on virtual threads.
- Fan-out across replicas uses Redis Pub/Sub channels `foodstack:<module>:rt:<topic>`; the replica that holds the SSE connection forwards messages. Heartbeat every 15 s; clients reconnect and reload once (no replay). On Redis Cluster the channels use sharded pub/sub, so a message reaches only the node that owns its channel.
- No channel carries the whole country: live maps follow the 0.25° map cells they show (`rt:cell:<cell>`, at most 64 per map), customers and stores follow one order or rider (section 7.5).
- Rider location: `POST /api/v1/rider/me/locations` every 10 s → Redis GEO set of the rider's cell (latest) + Pub/Sub on the cell (live) + sampled history in `rider_locations` with a TTL index (retention from settings, default 30 days).
- Push: `notification` sends FCM for devices with the app closed.

## 9. Security

### 9.1 Identity and access

- **Stateless auth API** (revised 2026-11-03, replacing the hosted login page and Authorization Code + PKCE): `POST /api/v1/auth/login` (`client: web|mobile`, email with a password or an emailed code), `/register`, `/code` (sends a sign-in or reset code), `/reset`, `/refresh`, `/logout`. Each app shows its own sign-in screen (shared `auth` library), themed by the app. No server session and no redirect, so any replica answers any call. Email codes are stored hashed in Redis for 5 minutes with 5 tries; passwords are Argon2id, checked with the same work when the email is unknown; sign-in is limited to 10 wrong passwords per email per 15 minutes.
- **Access token:** JWT signed with ES256, 10 minutes, kept in the app's memory only. Signing keys come from a Kubernetes Secret (or `.env` on a single server) and are published through JWKS; rotation keeps the previous key valid. Spring Authorization Server stays only for JWKS, its metadata and the client-credentials service token.
- **Refresh token:** 30 days for every user type, sliding (renewed with each refresh), stored in `identity_refresh_tokens` as its SHA-256 with the user, the client type, the token version and a family id (one family per sign-in, the `sid` claim). Each refresh claims the token once (`findAndModify` on `usedAt: null`) and issues the next one in the family. A used token presented again within 10 seconds answers `409 REFRESH_RACE` (two tabs refreshing at once; the app retries with the new cookie); later, it means the token leaked, so the whole family is deleted (`401 REFRESH_REUSED`). A blocked user or an older token version ends the family. Browsers get it as the `fs_refresh` cookie (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth/refresh`); mobile apps get it in the body and keep it in the Keychain or Keystore. `/refresh` reads the cookie, else the body; both at once is `400 REFRESH_TWO_CHANNELS`, and a token used on the other channel is `400 REFRESH_WRONG_CHANNEL`. The web app and the API must be same-site for the cookie. Tabs take turns refreshing (Web Locks), and a reload renews from the cookie without asking again.
- **Sign-out** (`/logout`, bearer token) deletes the family named by the token's `sid`, so the cookie never needs to reach `/logout`.
- Token claims: `sub`, `sid`, `roles`, `perms`, `tv` (token version), `brandIds` (brand owners), `storeIds` (branch managers and staff).
- **No per-request call to `identity`.** Every module is an OAuth2 resource server that validates the JWT itself, in memory: signature (ES256) against `identity`'s public keys, plus issuer, expiry and not-before. The public keys (JWKS) are fetched once at startup and cached; a token signed with an unknown key ID triggers one refresh. Validation costs microseconds and keeps working even if `identity` is briefly down. `identity` is called only to sign in, refresh a token, or fetch keys.
- Authorization uses method security with permissions such as `order:refund` and store-scoped checks (`storeIds` contains the target branch, or `brandIds` contains the target's brand; brand-level actions such as editing the menu need `brandIds`), read from the token claims, so no lookup is needed.
- **Immediate revocation** (account blocked, password changed, role removed, logout everywhere): `identity` stores a per-user `tokenVersion` (also a token claim) and publishes `UserTokensRevoked`. Each module keeps revoked versions in Redis (`foodstack:identity:revoked:<userId>`) with an in-memory cache of 5 seconds, bounded to 100,000 users (Caffeine; `foodstack.security.revocation-cache-max-users`), and rejects tokens whose version is older. The user's refresh tokens stop at their next use. Without this check a revoked token would still work until it expires (at most 10 minutes).
- **Edge check (Kubernetes):** an Istio `RequestAuthentication` + `AuthorizationPolicy` at the Gateway also validates the JWT for `/api/**`, so forged or expired tokens are rejected before reaching any pod. Public endpoints (menus, search, login) are listed explicitly.
- **Module-to-module, after a split**, three independent layers:
  1. *Who is the user:* Module-Switch forwards the caller's `Authorization` header, so the called module validates the same JWT locally and applies the same permissions. A module can never do more than the user who started the request.
  2. *Is the caller one of our modules:* the internal port 8081 requires `X-Module-Switch-Token` (a generated Secret) and is not routed by the Gateway, so it is unreachable from outside the cluster.
  3. *Is the network path trusted:* Istio ambient mTLS encrypts and authenticates every pod-to-pod connection with a workload identity, and `AuthorizationPolicy` allows port 8081 only from FoodStack server service accounts.
- Background work with no user (Kafka consumers, scheduled jobs) calls other modules with a short-lived service token from the `identity` client-credentials flow, carrying only the permissions that job needs. Events carry the acting user ID for audit, never a token.
- Web apps keep the access token in memory only and use refresh-token rotation; strict CSP; the refresh cookie is `SameSite=Strict` and sent only to the refresh path. Social login (provider tokens at `/api/v1/auth/social`) and TOTP for back-office users come in M5.

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
| Read-through caches (settings, branding, geocoding) | `foodstack:<module>:cache:<name>:<key>` | 1 to 10 min; evicted on the module's own changes |
| Generation caches (menus; quote zones and operator pricing) | `foodstack:store:menu:<storeId>:<gen>`, `foodstack:geo:cache:zones:<storeId>:<gen>`, `foodstack:geo:cache:operator-pricing:<gen>` | 10 min; a change raises the generation after commit, so a late reader never hides it |
| Idempotency | `foodstack:<module>:idem:<key>` | 24 h |
| Rate limiting (app) | `foodstack:<module>:rl:<scope>:<id>:<window>` | fixed window; edge limits live in the Envoy rate-limit service (Kubernetes only) |
| Login codes | `foodstack:identity:code:<purpose>:<userId>` | 5 min |
| Token revocation floor | `foodstack:identity:revoked:<userId>` | no expiry; cached 5 s per JVM, at most 100,000 users |
| Rider live location | `foodstack:rider:geo:<cell>` (GEO set per 0.25° cell), `foodstack:rider:pos:<id>` | refreshed on each ping; stale riders pruned |
| Dispatch offer locks | `foodstack:dispatch:offer:<riderId>` | offer timeout |
| Work buckets | `foodstack:workers:<work>`, `foodstack:workers:<work>:bucket:<n>` | lease 10 to 30 s, renewed (section 7.3) |
| Real-time fan-out | `foodstack:<module>:rt:*` (Pub/Sub; sharded on a cluster) | none |

Redis is a cache and coordination store only; losing it never loses business data. Production uses Redis with replicas (or a managed equivalent). The client is chosen by configuration only: one Redis (`spring.data.redis.url`), Sentinel (`spring.data.redis.sentinel.*`) or Redis Cluster (`spring.data.redis.cluster.nodes`). Cluster works because every command touches one key (no multi-key operation needs a hash tag yet) and live messages switch to sharded pub/sub.

## 11. Search (Elasticsearch)

- Indices prefixed by module: `search_stores_v<N>` and `search_dishes_v<N>`, accessed through aliases `search_stores` and `search_dishes`; reindex by building a new version and swapping the alias.
- Built only from events (`BrandChanged`, `StoreChanged`, `MenuChanged`, `ItemAvailabilityChanged`, `StoreAvailabilityChanged`, `ReviewPublished`), never by reading another module's database; a full rebuild job replays from `store` through its API.
- Per-language fields with language analyzers (including Arabic and Urdu), `search_as_you_type` for autocomplete, `geo_point` for distance sorting, and a delivery shape per store (`geo_shape`): its zone polygons for self-delivery stores, or a circle of the operator's maximum delivery distance for operator-delivery stores, so search returns only stores that deliver to the customer's address.
- Delivery results are also limited to a **discovery radius** from the customer (road distance, default 8 km, divided by the road factor for the straight-line filter); the customer widens it in configured steps (8, 15, 25 km). A branch link and checkout use the zone only (section 7.5, gap 10).
- Scale settings: shards (`foodstack.search.shards`) and replicas (`auto-expand-replicas`, default `0-1`) from config, applied to an existing index at start-up; results stop at `max-results` (500); brand lookups page through a point in time; each write carries the branch version as an external version.
- Ranking: open now, distance, rating, ETA, plus admin boosts.
- `SearchEngine` SPI in `search-core`; the Elasticsearch Java client is the default adapter.

## 12. Geo and maps

- `geo` owns store delivery zones (GeoJSON polygons, `geo_zones`), each belonging to one store with a fixed charge, minimum order and free-delivery threshold, and the **operator delivery pricing** (base + per km of road distance, maximum distance, optional peak multiplier, optional city override). `quote` uses the store's zones for self-delivery stores and the operator pricing for operator-delivery stores. `geo` also holds cities.
- The delivery mode is snapshotted on the order at placement, so the price the customer saw and the party paid always match. `ledger` credits the delivery charge to the store (self-delivery) or the operator (operator delivery), and posts operator rider pay as a percentage of the operator charge (optional minimum) plus tips. If support hands an operator-delivery order back to the store, the charge is re-credited to the store.
- Provider SPI: `GeocodingProvider`, `RoutingProvider`, `TileConfig`. Default adapter: Nominatim and OSRM (operator self-hosts or uses a hosted provider; public instances are only for demos). Optional Google Maps adapter.
- ETA = prep estimate + routing duration × city traffic factor (configurable). Quotes read a branch's zones and the operator pricing from Redis caches (10 minutes, generation keys, section 10).
- **Time zones:** every branch has its own IANA zone (`store_stores.timezone`), found from its location when it is created or moved, among the zones of the deployment's country (offline: IANA `zone.tab`, and timeshape boundaries for countries with several zones), and editable by the owner. Opening hours, open-now, closures, operator peak hours, kitchen tickets and order times are in the branch's zone. Branches saved before this use the deployment's zone until next saved.

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
| (none) | Push to `main` | Nothing is published on a merge (ilyas, 2026-11-08). Maven Central, Docker Hub, GHCR and the charts are published only by `release.yml` |
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
| `proxy` | `caddy:2.10-alpine` (stock Caddy) | The API gateway on a single server: automatic HTTPS for the operator's domains, routing of `/api`, `/oauth2`, `/.well-known` to the server and the rest to the web apps, request-size limits and security headers. No per-IP rate limits (ilyas, 2026-11-07): the server limits sign-in, codes, coupon checks and order placement itself |
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
| `install.sh <install-dir> [--domain example.com]` | Checks Docker and Compose v2, CPU, RAM, disk and ports; sets `vm.max_map_count` for Elasticsearch when run as root; creates `<dir>/{logs,config,backups}` (service data lives in named Docker volumes `foodstack_<service>`, so no sudo or folder ownership setup is needed); cleans up an unfinished earlier attempt so a re-run just works; `--local` presets a trial on `http://foodstack.localhost:8080`; generates strong secrets, JWT signing keys and the PII key into `<dir>/.env` (mode 600); copies the jar, apps and config; runs migrations once (`--foodstack.migrate-only=true`); starts the stack; optionally installs a systemd unit to start on boot; prints the first administrator sign-in |
| `start.sh` / `stop.sh` | `docker compose up -d` / `down` (data is kept) |
| `restart.sh [service]` | Restarts everything or one service |
| `status.sh` / `logs.sh [service]` | Health summary / follow logs |
| `backup.sh` | `mongodump`, a copy of the media storage and config into `<dir>/backups/<timestamp>` |
| `restore.sh <backup-dir>` | Stop the application, restore MongoDB and media, rebuild search, start |
| `upgrade.sh <new-zip>` / `--rollback` | Backup, replace jar, apps and compose file, run migrations, restart / restore the previous version |
| `uninstall.sh [--yes]` | Removes the containers and data volumes after confirmation; keeps `backups/` |

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
├─ values-dev.yaml        in-cluster MongoDB (single-node replica set), Redis, Kafka (KRaft), Elasticsearch, RustFS, Mailpit
├─ ci/production-values.yaml  CI only: a production-shaped render (external datastores)
└─ templates/
   ├─ server/             Deployment, Service, HPA, PDB, ServiceAccount, ConfigMap per entry in .Values.server.deployments
   ├─ web/, backoffice/   Deployment, Service, HPA, PDB
   ├─ migrations-job.yaml pre-install / pre-upgrade hook
   ├─ gateway/            Gateway, HTTPRoute (generated from the module → prefix map), certificates, RequestAuthentication, rate-limit service
   ├─ mesh/               PeerAuthentication STRICT, AuthorizationPolicy, waypoint (pods join ambient by their own label)
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

- Spring Boot with Micrometer and the OpenTelemetry exporter: traces (W3C context through HTTP, Module-Switch calls, the outbox and Kafka headers, section 7.4; export by `FOODSTACK_TRACING_EXPORT`), metrics (including `module.switch.call`, outbox lag, consumer lag, dispatch timings, order funnel), structured JSON logs with `traceId`.
- `foodstack-observability` chart (optional, `k8s/helm/foodstack-observability`): an OpenTelemetry Collector on every node (container logs to Loki, OTLP spans to Tempo), Prometheus (pods by `prometheus.io/*` annotations; it joins the mesh to scrape the server), Grafana with three FoodStack dashboards (overview: golden signals per Deployment; orders and delivery; events: outbox, consumer lag, failed events), Tempo, Loki, optional Alertmanager, and alert rules (pod down, error rate, latency, outbox age, failed events, consumer lag, heap, certificate expiry). The dashboards are generated by `dashboards/generate.py`.

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
    listener-concurrency: 3                 # per module: foodstack.kafka.concurrency.<module>
  redis:
    url: ${REDIS_URL}                       # or spring.data.redis.sentinel.* / cluster.nodes
  search:
    engine: elasticsearch
    url: ${ELASTICSEARCH_URL}
    shards: 1
    auto-expand-replicas: 0-1               # 0-all on a large cluster
    max-results: 500
    discovery-steps-km: [8, 15, 25]
  geo:
    quote-cache-ttl: 10m
  security:
    issuer: https://api.example.com
    pii-key: ${PII_KEY}                     # from Secret
    revocation-cache-max-users: 100000
  identity:
    refresh-cookie-secure: true             # false only for a plain-HTTP trial
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
| T2 | One-time-code login on Spring Authorization Server | **Superseded (2026-11-03):** sign-in is the stateless auth API (9.1), where an emailed code is one more way to call `/auth/login` |
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
| Web tokens | Refresh-token rotation, access token in memory | Public SPA clients get no refresh token from Spring Authorization Server. The apps keep the access token in `sessionStorage` (tab-scoped) and renew it in the background a minute before it expires: a same-origin `fetch` of `/oauth2/authorize` follows the redirect to the callback, and the code is exchanged for a new token, so the page and any open form stay as they are. A token close to expiry is renewed before an API call, and a 401 renews once and retries. Only when the identity session has ended does the user go back to the login page. Needs the issuer on the same origin as the app (true for both topologies). Sign-out uses OIDC RP-initiated logout (`/connect/logout`) | Refresh tokens through a backend-for-frontend, or in-memory tokens with silent renew in an iframe |
| Identity session | Not specified | The login session lives in the server's memory, so it ends on restart and is not shared between replicas | Spring Session on Redis before running identity with more than one replica |
| Login methods | Email codes, password, social, TOTP | Email and password (Argon2id) only. Email codes need the notification module and SMTP | M2 (notifications), M5 (TOTP, social) |
| First admin | One-time link to set the password | `install.sh` generates the password, stores it in `.env` and prints it once | Password change and reset with the notification module |
| Elasticsearch | Always in the Compose stack | Compose profile `search`, off by default; no module uses it yet | Done in M1: on by default (section 22) |
| OpenAPI and API client | springdoc per module, generated Angular client | Hand-written typed clients in `frontend/projects/api-client` | Add springdoc and generate the client in CI |
| Rate limiting | Edge plus per-user limits in Redis | Only Caddy request-size limits on one server | Per-IP and per-user limits with the first public write endpoints beyond sign-up |
| Libraries | `platform-core`, `-mongo`, `-kafka`, `-security`, `-test` | Also `platform-web` (problem+json errors, ETags), shared by every `-web` module | None |
| Module dependencies | | `settings` calls `media` through `MediaApi` (claim and release the logo), the first synchronous module-to-module call; it runs over HTTP in the microservices topology | None |
| Authorization documents | | `identity_authorizations` is not sharded (short-lived, small) | Shard by `_id` hash if volume calls for it |
| Brands and branches | Section 5.7 | Not built; designed after M0 | Data model done in M1 (section 22), multi-branch screens (M4) |

## 22. M1 implementation notes

M1 adds the `geo`, `store` and `search` modules, the back-office catalogue screens and the customer browse pages. Exit check: the `CatalogueScenario` integration journey onboards a store with an Arabic menu, finds it by searching "برياني", opens its page and menu, and runs as a monolith and as one service per module. Differences from the design above, each deliberate:

| Area | Design | M1 | Follow-up |
|---|---|---|---|
| Delivery zones | GeoJSON polygons drawn on a map | As designed: the owner draws each zone on a MapLibre map in the back office (click to add a corner, drag to move, click a corner to remove, or start from a circle), with its own charge, minimum order and free-delivery threshold. Both the editor and `geo` refuse outlines whose edges cross. The REST API still accepts a centre and radius, turned into a 16-corner polygon. Map tiles come from `MAP_TILE_URL` (OpenStreetMap by default) | Hosted or self-hosted tiles for operators with many stores; the shared maps wrapper (section 14) when the live map arrives |
| Road distance | Routing provider (OSRM) | Straight-line distance × `foodstack.geo.road-factor` (1.3). ETA = prep time + distance at an urban average speed | `RoutingProvider` with OSRM when rider dispatch lands (M3) |
| Geocoding | `GeocodingProvider` SPI in geo | SPI plus the Nominatim adapter in `geo-core` (`foodstack.geo.geocoding.provider`: `nominatim` or `none`), results cached in Redis | Google adapter on request |
| Dish search | Separate `search_dishes` index | Dish names and descriptions in every language are fields of the store document in one `<prefix>search_stores` index (alias over `_v1`); a match on a dish finds its store | Separate dish index when the dish carousel lands |
| Index updates | Built only from event payloads | Events from `store` and `geo` trigger a re-read of the branch through `StoreApi`/`GeoApi`, so the index is idempotent and needs no inbox; only `LIVE` branches are indexed. The index is created and rebuilt in the background on start, and `POST /api/v1/search/reindex` (`search:manage`) rebuilds on demand | Alias swap to a new version when the mapping changes |
| Ranking | Open now, distance, rating, ETA, boosts | Open now first, then text relevance, then distance; branches of one brand collapse to the nearest. Cuisine chips count branches (the cuisine filter is a post filter, so every chip keeps its count) | Rating and boosts with reviews (M5) |
| Store web addresses | Not specified | Customer store pages live at `/stores/<slug>` (for example `/stores/spice-route-gulberg`). The slug is made from the brand and branch names at onboarding (accents removed; `-2`, `-3` when taken; `store` for names with no Latin letters) and the operator can change it in Platform settings (`PUT /api/v1/stores/{id}/slug`). Uniqueness comes from `store_slugs`, whose `_id` is the slug (a unique index on `store_stores.slug` would need the shard key). Customer endpoints accept the slug or the id; the page sets `<link rel="canonical">`. A changed slug frees the old one, which then answers 404: no redirects while in development (ilyas, 2026-11-01). Branches without a slug get one at start-up | Redirect old slugs (301) once there are real customers; sitemap.xml |
| Pickup search | Not specified | Pickup searches branches with pickup on within `foodstack.search.pickup-radius-meters` (15 km) | Setting in the admin panel |
| Store owner access | Section 5.7 | A branch owner gets role `STORE_OWNER` with the brand id and the branch id as token scopes (`brandIds`, `storeIds`), checked by `CurrentUser.requireStoreAccess` and `requireBrandAccess`. Operator staff with `store:manage` reach every store | Brand-level owners and staff roles with multi-branch screens (M4) |
| Menu edits | `If-Match` on every write | Branch, brand and hours edits use `If-Match`; menu sections and items carry their `version` in the body because one screen edits many of them at once | None |
| Money | Minor units | Minor units follow ISO 4217 (`java.util.Currency`): PKR is stored in paisa although it is usually shown without decimals. The front-end converts with ISO digits, never with the CLDR display digits | None |
| Elasticsearch | 9.x | Server image `elasticsearch:9.4.0` from Docker Hub (the Elastic registry is not reachable from every build machine), client 9.4 from Spring Boot. Runs with `node.store.allow_mmap=false`, so no `vm.max_map_count` change and no root step; `install.sh` still raises it when run as root | None |
| Customer language | Section 14 | The customer app sets `lang` and `dir` on the page from the operator's languages; the pick is kept in a `fs-language` cookie so server rendering answers in it. The back office stays English and left to right and shows one input per language for translated text | Translations of operator-defined texts beyond menus (M2) |

## 23. M2 implementation notes

M2 adds the `promotion`, `cart`, `payment`, `order` and `notification` modules, email-code sign-in and password reset, the customer ordering screens and the store's live order board. Exit check: the `OrderingScenario` integration journey has a customer pay for a pickup order by card on the sandbox page (tapping twice places one order), and the store accepts, readies and hands it over; a cash order the store turns down is closed with the reason, and a blocked customer can no longer order; `AccountRecoveryScenario` signs in with an emailed code and resets a password. Both run as a monolith and as one service per module. The flows are drawn in [Architecture 6](architecture/06-ordering-flows.md). Differences from the design above, each deliberate:

| Area | Design | M2 | Follow-up |
|---|---|---|---|
| Pricing | 5.3: `order` calls `store`, `geo` and `promotion` and computes the totals | Pricing lives in one place, `cart` (`CheckoutService`): current menu prices from `StoreApi.validateOrder`, delivery from `GeoApi`, the coupon from `PromotionApi`, then the operator's service fee (fixed and percent), tax (on food after discounts) and the tip. The checkout screen and `order` call the same code, so the customer pays exactly what they saw; a different total is refused with `PRICE_CHANGED` and the app re-quotes | None |
| Ordering settings | `settings` holds business rules | `settings` gains `ordering`: cash and card switches, service fee, tax, tips, time to accept (default 3 minutes); edited on the back-office Ordering page | Per-city fees with cities |
| Order events | 8 events | One event per step, each carrying the same order summary: `OrderPlaced`, `OrderConfirmed`, `OrderAccepted`, `OrderPreparing`, `OrderReady`, `OrderPickedUp`, `OrderDelivered`, `OrderCollected`, `OrderRejected`, `OrderCancelled`, `OrderFailed`, `OrderRefunded`. There is no shared `OrderEvent` interface: Module-Switch treats every interface in an `-api` package as a module API | None |
| Order lists | Read projections | `order_active` (open orders, for the board and the accept-timeout sweep) is written in the order's own transaction, so the board never misses an order. `order_by_customer` and `order_by_store` are fed by the module's own events and can be rebuilt (`POST /api/v1/orders/projections/rebuild`) | None |
| Idempotency | 8.1: `Idempotency-Key` cached 24 h in Redis for orders, payments and refunds | Order placement keeps the key and a fingerprint of the body in `order_placement_keys`, inside the order's transaction, so a double tap returns the first order and a reused key with another body is refused. Payments are one per order (`payment_intents._id` = order id); refunds use the event id as refund id | None |
| Payments | 5.4: merchant accounts, `resolveMerchant`, hosted or embedded | Operator account only; gateway SPI `CardGateway` with `stripe` (Checkout Sessions, signed webhooks, refunds) and `sandbox` (a built-in page for development and tests, refused when `FOODSTACK_ENVIRONMENT=production`). Hosted page only. Pages expire after 30 minutes (`payment-expiry` sweep). Refunds are kept inside the payment document | Store and brand merchant accounts, embedded fields and saved cards (M4) |
| Accept timeout | Not specified | `order-accept-timeout` runs every 15 s on every replica; conditional updates make it safe without a lock. Unanswered orders are rejected (`NO_ANSWER`) and paid ones refunded | Leader lock if the reads matter |
| Live updates | 8.3: SSE, Redis pub/sub, `Last-Event-ID` | SSE on `/api/v1/orders/{id}/stream` and `/api/v1/orders/stores/{storeId}/stream`, fan-out over `foodstack:order:rt:*`. A message carries only ids, status and revision; the client reloads the order, and reloads once after reconnecting instead of replaying with `Last-Event-ID`. Heartbeat every 15 s. The apps read the stream with `fetch` so the token goes in a header, not the URL | None |
| Rate limits | 8.1: token bucket | Fixed window in Redis (`RateLimiter`): 5 sign-in codes and 10 wrong passwords per email per 15 minutes, 30 coupon checks per customer per 10 minutes, order placements per customer; 429 with `Retry-After` | Edge limits (M5) |
| Login codes | `foodstack:identity:otp:<emailHash>` | `foodstack:identity:code:<purpose>:<userId>`: a hash of the 6-digit code, 5 tries, 5 minutes, single use. Unknown or blocked emails get the same answer and no email. The code travels encrypted in `EmailCodeIssued`; the email is "sealed" in the delivery log (body encrypted, wiped once sent or failed) | TOTP and social login (M5) |
| Notifications | Templates, channels, preferences, delivery log | In-app inbox and email; templates are in code in every operator language with the operator's branding; preferences for order and offer emails (codes always sent); delivery log with retries at 1, 5, 30 and 120 minutes and a retry button on the Email log page | Push (FCM) in M3; template editor in M4 |
| Menu cache | 5.7: evicted on events | Keyed by a generation number raised right after a menu commit (`foodstack:store:menu:<storeId>:<generation>`), which closed a race where a slow reader wrote an old menu back after the eviction | None |
| Coupons | 5.5 | Operator and store coupons: percent or amount off food, or free delivery; total and per-customer limits, first order only, stores, cities, payment methods, operator-merchant-only and operator funding share. A use is held at placement with conditional counters, redeemed on `OrderConfirmed`, given back on cancel, reject or fail | Offers without codes, with home sections |
| Live ETA, push | PRD | Not in M2 | M3 with riders |

## 24. M3 implementation notes

M3 adds saved customer addresses (PRD ID-6), the `rider` and `dispatch` modules, live tracking, the rider app inside the back office, the dispatch board with the live map, and push over FCM. Exit check: the `DeliveryScenario` integration journey has a store that uses the operator's riders accept a cash delivery order; dispatch offers it to the online rider, who accepts, sends a position the customer sees, picks it up and hands it over with the customer's code (a wrong code is refused); the order ends `DELIVERED` and the rider's earnings show the job and the cash. It runs as a monolith and as one service per module. The flows are drawn in [Architecture 7](architecture/07-delivery-flows.md). Differences from the design above:

| Area | Design | M3 | Follow-up |
|---|---|---|---|
| Saved addresses | PRD ID-6 | In `identity` (`identity_addresses`, `/api/v1/me/addresses`): up to 20 per customer, one Home, one Work, any number of named others, each with a map pin and rider notes. An order keeps its own copy of the address | None |
| Cities | 5.6: candidates "in the store's city"; DR-1b per-city pricing | No `City` entity yet: dispatch searches by distance from the store only, and operator pricing is one set for the whole deployment. The rider's city is free text | Cities in M4 (ilyas, 2026-11-03), with per-city pricing |
| When the search starts | 5.6: accepted time + prep − travel time to the store | `readyBy − searchLeadMinutes` (a setting, default 10 minutes), never before now. Travel time is not known until a rider is chosen, and a fixed lead is easier for the operator to reason about | Revisit with real data |
| Score | 5.6: weights in settings, includes active jobs | Fixed in code: distance to the store in metres, minus idle seconds (up to 30 minutes), plus 20 per point of acceptance below 100%. An operator rider holds one job at a time (no batching, PRD DR-9 is "Could"), so active jobs are always zero for a candidate | Weights in settings if operators ask |
| Matcher | 5.6 | One replica runs it every 2 s (`<ns>:dispatch:matcher:leader` in Redis, 15 s lease). A job with no rider in range widens its radius by half after each 45 s round, at most twice; riders who declined are tried again after 3 minutes; one `DispatchAlert` after **Alert support after** | None |
| Offer lock | 5.6: `SET NX PX` for 30 s | `<ns>:dispatch:offer:<riderId>`, SET NX for the offer timeout + 5 s (timeout is a setting, default 30 s) | None |
| Rider positions | 10: Redis GEO | `<ns>:rider:geo:online` holds only riders who can take an offer (online, active, operator, free); `<ns>:rider:pos:<id>` keeps every online rider's last point for 10 minutes. MongoDB gets a sample every 30 s (`rider_locations`, deleted after **Keep rider positions for**, default 30 days). Riders silent for 5 minutes are set offline | None |
| Routing | 12: `RoutingProvider` | `ROUTING_PROVIDER=osrm` with `ROUTING_URL`, or `none` (default): straight-line distance × road factor at an urban average speed. Operators without an OSRM server get estimates, not road distances | Hosted routing adapter if asked |
| Store riders | PRD OF-8 | The store chooses one of its riders in the accept dialog (sent as `storeRiderId` with the accept action), or later with **Choose rider** on the board. Store riders get no offers and no pay in FoodStack | None |
| Proof of delivery | PRD DR-6 | Code (stored only as a hash in dispatch, 5 tries per 10 minutes), optional photo uploaded through `media` as `RIDER_DOCUMENT` and kept as `media:<id>`, cash collected. Support can finish without the code. The code is in the customer's order page and, since the follow-up PR, in the order confirmation email of delivery orders (decrypted only to render the email) | None |
| Delivery code in events | 9: PII encrypted in events | Fixed in the follow-up PR: the order keeps the code encrypted (`deliveryCodeCipher`), and `OrderConfirmed` and `OrderAccepted` carry only the cipher. Dispatch decrypts it once to store its hash; notification decrypts it to write the email | None |
| Push | 8.3, 14: FCM | `notification` sends FCM HTTP v1 with a service-account token (`FCM_SERVICE_ACCOUNT`, a key file path or its JSON); web push needs `FCM_WEB_CONFIG` and `FCM_VAPID_KEY`. Devices in `notification_devices` (`_id` = SHA-256 of the token, token encrypted, 10 per user, dropped when FCM says unregistered). Push is fire-and-forget: there is no push entry in the delivery log, because every push message is also in the in-app inbox | Push log if support needs it |
| Push messages | PRD NO-1 | Rider: new offer, job assigned by someone else. Customer: rider assigned. Admin and support: no rider found. Order updates still go in-app and by email as in M2 | Push for every order step if wanted |
| Rider app | 14: PWA | Part of the back office (`/admin/rider`, role `RIDER` or `STORE_RIDER`), installable with its own manifest and `sw.js` (push, offline shell). Positions sent every 10 s, queued offline (up to 120) and sent on reconnect | None: ilyas chose to keep the PWA (2026-11-07), no native wrapper |
| Kafka topics | 7.1: each module owns its topic | Unchanged, plus consumers no longer create topics by subscribing (`allow.auto.create.topics: false`) and refresh metadata every 30 s. Found by the delivery journey: when `order` or `rider` started before `dispatch`, subscribing created `dispatch.events` with one partition, and events on the partitions added later were missed | None |
| `dispatch-impl` | 4: every module has `-impl` | Present but empty: no other module calls `dispatch` synchronously (order talks to it only through events) | Fill when a caller appears |
| Operator rider onboarding | PRD DR-1: identity documents, payout details | Follow-up PR: **Papers** on the rider page, operator riders only, `rider:manage` only. Up to 10 documents (national ID, passport, driving licence, vehicle registration, other), each with a number kept encrypted and shown by its last 4, a front photo (back optional) uploaded as private `RIDER_DOCUMENT` media and claimed by `rider`, an optional expiry date, and a **Mark checked** step. Payout: bank or mobile wallet, provider, holder name, account encrypted (entered in full on every change). Collection `rider_onboarding` (`_id` = rider id, hashed). Papers are *complete* with a checked, unexpired ID card or passport plus payout details; the riders list marks the rest **Papers missing**. Incomplete papers do not stop a rider going online | Block going online until complete, if the operator wants it |
| Operator pricing | PRD DR-1b: optional minimum order, free-delivery threshold, peak multiplier, per-city override | Follow-up PR: minimum order, free delivery from a subtotal, and peak hours (up to 10 windows of days and times in the deployment time zone, a window may run past midnight) with one multiplier from ×1 to ×3. Quotes for operator-delivery branches carry them, and checkout already enforces a quote's minimum and threshold. Rider pay is a share of the *normal* delivery charge (before the free-delivery threshold or a delivery coupon; ilyas, 2026-11-03), carried to dispatch as `OrderDelivery.riderPayBasisMinor`: it rises in peak hours, and a free or discounted delivery pays the rider the same, the operator covering the waived part | Per-city override with cities in M4 |
| Web bundle | 14: budgets | The web app's initial bundle is about 844 kB against an 800 kB warning budget (836 kB before M3; maps and push load only when used) | Trim in M4 |

## 25. Scale-out implementation notes

The scale review (section 7.5) was built on the M3 follow-up pull request (PR #7) after ilyas's go-ahead on 2026-11-04. Exit check: every module's tests (lowest layer against real MongoDB on two shards, Redis, Redis Cluster, Kafka and Elasticsearch in containers) and every integration journey as a monolith and as one service per module, including sign-in, refresh rotation, sign-out and revocation. Differences from what was locked, and choices the lock left open:

| Area | Locked | Built | Why |
|---|---|---|---|
| Auth paths | `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/social` | `/api/v1/auth/*`; the refresh cookie's path is `/api/v1/auth/refresh` | Keeps sign-in in the API filter chain, the gateway's `/api` routes, rate limits and CORS, with no extra route on Caddy or the Gateway |
| Sign-out | `/auth/logout` | Called with the access token; deletes the sign-in named by its `sid` claim and clears the cookie | The cookie is sent only to the refresh path, so `/logout` never needs it |
| Two tabs refreshing | Reuse detection ends the family | A used token seen again within 10 s answers `409 REFRESH_RACE` and the family survives; the app also takes a Web Lock so tabs take turns | Two tabs of one browser would otherwise sign the user out at random |
| Social login, TOTP, signed challenge for multi-step sign-in | `/auth/social` with provider tokens; multi-step through a signed challenge token | Not built: both were already planned for M5, and no sign-in has a second step until TOTP | Built with M5 |
| Spring Authorization Server | Drop the hosted login and the server session | Kept only for JWKS, its metadata and the client-credentials service token; no user clients, no login page, no session | Split deployments still need the service token, and JWKS is unchanged for every module |
| Refresh lifetime | 30 days for every user type | 30 days, sliding: each refresh starts a new 30 days | A user active at least once a month stays signed in |
| Live maps | Per-geo-cell channels | 0.25° cells (about 28 × 28 km), at most 64 per map; a wider view gets the board without live dots and the hint "Zoom in to follow riders live" | Bounds what one map can subscribe to; the same cells hold the rider GEO sets |
| Edge rate limits, one server | Caddy | None: stock Caddy, and the server's own per-user limits (sign-in, codes, coupon checks, order placement) | ilyas, 2026-11-07: a single server does without edge limits; Kubernetes gets them in M5. The `foodstack-proxy` image built in PR #7 was removed in M4 |
| Edge rate limits, Kubernetes | Envoy rate-limit service | With the Helm chart in M5 | The chart is not built yet |
| Search depth | Cap at about 500 | `foodstack.search.max-results` (500): a page past it is empty, the page before says there is no more | — |
| Discovery radius | 8 km road, then wider steps | Steps from `discovery-steps-km` (8, 15, 25); the road distance is divided by `road-factor` (1.3) for Elasticsearch's straight-line filter; pickup keeps its own 15 km and gets no wider step | Elasticsearch measures straight lines |
| Branch time zone | Offline boundary lookup from the location | Only zones of the deployment's country are candidates (a deployment serves one country): one-zone countries need no boundaries; others load just their zones (about 1 s and 20 to 30 MB, kept softly). A point outside the country keeps the deployment's zone. Existing branches have no zone until next saved and use the deployment's meanwhile. If the owner leaves the zone unchanged while moving the branch, it is found again | A whole-world boundary set needs about 225 MB of heap |
| Settings in split deployments (12d) | Check the locale call is not a network call per view | It was: `getLocale()` on a branch page called `settings` over HTTP each time. Services that run `settings` remotely now keep its answers for 30 s (`RemoteSettingsCache`) | Same staleness as the branch page's `s-maxage` |
| Notification sending (15) | Consumer writes the inbox and queues; `sendDue` workers send | Also a bounded virtual-thread sender sends a new delivery at once, off the consumer thread; push has two quick retries (10 s, 30 s) | Keeps emails as fast as before |
| Tracing (8) | OTLP exporter by config, off by default on one server | Off by default everywhere (`FOODSTACK_TRACING_EXPORT`), sampling 10% | Nothing to receive spans unless the operator runs a collector |

## 26. M4 implementation notes

M4 adds cities, multi-branch screens (brand owners, franchise owners, branch prices with optional approval, branch-only items, branch promotions, shared store riders), per-branch ticket paper, the audit log, the `ledger` module with netted payouts and settlement, direct collection by branch or brand merchant accounts with a credit limit, the `review` module, data export and account deletion, embedded card fields and saved cards, the message (template) editor, and a trimmed first download for both web apps. Decided with ilyas on the way: commission is taken on the food after store-funded discounts; a partial refund after an order is done is paid by whoever support picks, the store by default; the price approval switch (ST-6) is off by default; food tax goes to the store; deletion keeps a 14-day grace and anonymised orders and reviews; a blocked word in a review is masked and the review flagged; card data never reaches FoodStack. Exit check: the `MoneyAndTrustScenario` journey has a customer pay two orders in the app's own card fields (the second with the saved card), the branch's payout row show both orders' food less commission (also in the owner's own report and its CSV), the customer review the store with a blocked word masked and flagged and the owner reply; and a customer download their data, ask for deletion, get the operator's own wording of the email, lose their sign-in, and leave the store's order as "Deleted user". It runs as a monolith and as one service per module. Differences from the design above:

| Area | Design | M4 | Follow-up |
|---|---|---|---|
| Cities | 5.6, DR-1b per-city pricing | `geo_cities` (unsharded: a deployment has tens of cities), a branch and an operator rider each have a city; dispatch prefers riders of the store's city; operator pricing may be overridden per city. A branch's city cannot be cleared in the admin form, only changed | None |
| Branch to brand lookup | 9.1: no lookup on the request path | `BranchBrands` keeps branch → brand in Redis (filled at start by `BranchAccessBackfill`, kept by store events), so a brand owner's token need not list every branch | None |
| Price approval (ST-6) | Price changes wait for approval | Only price changes of existing dishes wait; new dishes and options start with the price they are added with | Hold new items too if operators ask |
| Branch promotions (BB-11) | Promotion reads store | `promotion` keeps `promotion_brand_rules` from the store event `BranchPromotionsChanged` | None |
| Audit log | Through the outbox | Back-office writes and sign-ins go to Kafka topic `<ns>.audit` directly (an ERROR log if Kafka refuses), stored in `identity_audit_log`; "before" is the previous entry for the same path; no retention limit yet | Retention setting |
| Ledger partitions | Shard by `storeId` | Transactions are sharded by `partition`: the branch, else the order or account | Same effect; riders and operator accounts have no branch |
| Ledger reports | Daily roll-ups | Roll-ups per (bucket of 64, day) rebuilt in one MongoDB transaction by every replica over its share; reports add the transactions not rolled up yet; days in the deployment's time zone. Payout PDFs use bundled Roboto, so letters Roboto lacks (Urdu) print as "?" | Noto fonts for PDFs |
| Direct collection | DP-1..DP-8 | Owner webhooks on `/api/v1/payment-webhooks/{gateway}/{brands|stores}/{ownerId}`; an account is verified by the gateway before it is saved, and marked FAILED when the gateway refuses it for good; connecting needs the branch to be set to DIRECT first; `StoreCreditRestored` turns a suspended branch back on; finance may read a branch's details to set its collection | None |
| Reviews | RV-1..RV-4 | `review` keeps its own `review_orders` from order and dispatch events (the order API has no rider). Store stats by `$inc`; the search card's rating lives only in the Elasticsearch `_source` (no mapping change) | None |
| Data export | ID-7: one file | Each module serves the signed-in customer's own part (`/me/export`, `/orders/mine/export`, `/reviews/mine/export`, `/notifications/mine/export`) and the web app joins them, failing the whole download if one part fails. `identity` cannot gather them: it may not depend on other modules | None |
| Account deletion | ID-7 | `POST /api/v1/me/deletion` (customers only) revokes every token and schedules the deletion; signing in again during the grace period cancels it, and a sign-in after it is refused (`ACCOUNT_DELETED`, 401) until the sweep erases the account. Every replica sweeps due accounts every `foodstack.identity.deletion-sweep` (30 min) with a conditional update; the user keeps only its id and status `DELETED`. On `UserDeleted`: order and dispatch blank the name, phone, address text, notes and instructions and round the drop-off to 2 decimals; review renames to "Deleted user" and releases the photo; notification deletes inbox, devices, preferences and log; payment deletes the gateway customer and its saved cards. The audit log is kept as the legal record. `dispatch-core` and `payment-core` now depend on `identity-api` | None |
| Embedded card fields | CO-5 | Stripe Embedded Checkout (`ui_mode=embedded`) mounted on the web app's `/pay/:orderId`, so the existing `checkout.session` webhooks are unchanged and card data stays in Stripe's frame. Needs `STRIPE_PUBLISHABLE_KEY`; without it customers get the hosted page. The sandbox gateway has pretend fields on the same page. Only payments into the operator's own account: a branch or brand account has no publishable key in FoodStack, so direct-collecting branches always use the gateway's page | Brand publishable keys if asked |
| Saved cards | CO-5a | Kept by the gateway on a gateway customer per user (`payment_customers`, hashed `_id`), only on the operator's account (a card saved there would not work on a branch's account). The app sees brand, last four and expiry; customers remove cards on the account page | None |
| Message editor | NO-2 | One wording per message and language in `notification_templates` (unsharded, a few dozen documents), used field by field over the built-in text and read on each message (one `_id` lookup). An edit may use only the message's own placeholders and a code message must keep its code. A language FoodStack has no text for shows English, and the operator's English edit, until reworded. The shared phrases (button text, delivery code line) are not editable | Edit phrases if asked |
| Web bundles | 14: 800 kB budget | Web 692 kB (was 864): the address dialog loads on first use. Back office 732 kB (was 806): the side menu is plain links styled as a Material 3 drawer, since `mat-nav-list` brought all of Angular Forms into the first download. Budget unchanged | None |
| Mongo records | 6 | A new field on an existing record is a boxed type with a default in an accessor, so older documents still map | None |
| Search removal of draft branches | 7.5 | A branch that is not live is removed from the index with its own version (external, like writes). Unversioned removals of a never-indexed branch each left a tombstone one version higher, so a branch with more draft edits than its version number was refused when it went live, until Elasticsearch forgot the tombstones (about a minute) or the daily re-read | None (bug fixed in M4) |

## 27. M5 implementation notes

M5 adds the Helm chart for every topology (Gateway API routes, the edge token check, Envoy rate limits, Istio ambient policies, trial datastores), two-step sign-in with an authenticator app, Google and Apple sign-in, the failed-events screen with replay, search ranking by rating and an operator boost, the meters and the observability chart, a k6 load test, and chart checks in CI. Decided with ilyas on the way: no native wrapper for the rider app; the PWA stays. Exit check: the chart installed on a k3s cluster with Istio 1.30 ambient in `split`, `microservices`, `custom` and `monolith`, with sign-in (including two-step), search, ordering and dispatch reached through the gateway, port 8081 refused from outside the server pods, the observability chart receiving metrics, logs and traces, and the k6 test passing (800 of 800 checks, 95th percentile under 45 ms for every step at 10 users). The `TwoStepAndSocialScenario`, `DeadLetterScenario` and `CatalogueScenario` journeys run as a monolith and as one service per module. Differences from the design above:

| Area | Design | M5 | Follow-up |
|---|---|---|---|
| Edge rate limits | 18.3: per client IP and per OAuth client; request-size limits, retries and timeouts at the gateway | Envoy's rate-limit service per client IP only, with tighter limits on sign-in and order placement (`gateway.rateLimit.limits`); fails open. Request-size limits, retries and per-route timeouts are left to Istio's defaults, except no timeout on the live streams | Per-client limits if a partner API appears |
| Edge token check | 18.3 | `RequestAuthentication` rejects forged or expired tokens; requests without a token pass and the server decides. istiod must run with `PILOT_JWT_ENABLE_REMOTE_JWKS=envoy`: by default istiod fetches the keys once, and if identity was still starting every token failed for up to 20 minutes. The keys are fetched through the internal gateway, so no public DNS is needed | None |
| Secrets | 18: External Secrets | The chart generates the signing keys, personal-data key, internal token and first password as hook Secrets, kept on upgrade (an upgrade without them fails rather than making new keys); `secrets.existingSecret` brings your own | None |
| Migrations | 6.4: hook Job | Hook Job on install and upgrade; the trial values also migrate at start-up. The migrate-only run had stopped starting in M4 (search, review and other module services ask for `ServiceCalls`, which only exists with a web server), which broke `install.sh` and the hook Job; it now gets a stand-in that refuses work, and the integration tests run migrate-only before every platform start, as an install does | None |
| Mesh | 18.3 | Pods join ambient by their own label, not the namespace's. ztunnel ignores port-level `PERMISSIVE`, so the management port (8082) takes only mTLS and Prometheus joins the mesh; the AuthorizationPolicy opens 8082 to any workload in it. After a node restart, pods that started before the CNI agent need a restart | None |
| Two-step sign-in | ID-5 | TOTP (RFC 6238, SHA-1, 6 digits, 30 s, one step of drift), required for ADMIN and FINANCE (`foodstack.identity.totp.required-roles`), optional for other back-office roles, not offered to customers. A sign-in that needs it answers `{next: TOTP or TOTP_ENROLL, challenge}`, a signed 5-minute challenge (HS256, audience `foodstack-sign-in`) finished at `/auth/two-step` or `/auth/two-step/enroll` + `/confirm`. Each code works once (last step stored), 10 one-time recovery codes (hashed), an administrator can reset it and the user's sign-ins end. The QR code is drawn in the page. The integration tests require it for FINANCE only; local runs require it for nobody | None |
| Social sign-in | ID-2 | Google and Apple ID tokens (RS256 against the provider's keys, issuer, audience among the client ids, expiry, verified email) at `/auth/social`. Client ids live in identity settings, set on the back office **Sign-in options** page, not in configuration. An unknown provider account signs in to the account with the same email, else a new customer; Apple's name comes from the first sign-in only. The buttons are on the customer app only. Sign-up never proves an email address, so an account records `emailVerifiedAt` (emailed code, password reset or provider); linking a provider to an account nobody proved the address of clears its password and ends its sign-ins (found in the M5 security review) | Buttons in the mobile apps |
| Failed events (G1) | 7.3: DLT replay | The settings module reads every `.dlt` topic into `settings_dead_letters` (event encrypted, 30 days). **Settings, Failed events** shows the event, replays it to the one consumer group that failed (header `foodstack-replay-for`; other groups skip it through a record filter) or dismisses it. A replay that fails again comes back as a new entry; failures of the failed-events listener itself are not collected | None |
| Search ranking | 11: ETA, rating, admin boost | Score = nearness (full within 1 km, half at 5 km, Gaussian) × (0.5 + 0.1 × rating) × boost; the rating is a Bayesian average (weight 5 reviews, prior 4.0), the boost is per brand, 0 to 3 (× 1, 1.25, 1.5, 2), set on the brand page by the operator. Open stores always come first. Nearness stands in for ETA | ETA once routing is live everywhere |
| Meters | 18.5 | `foodstack.outbox.pending`, `foodstack.outbox.oldest.age`, `foodstack.outbox.published`, `foodstack.dead.letters`, `foodstack.orders{status}`, `foodstack.dispatch.jobs{status}`, `foodstack.dispatch.assign.time`, `foodstack.dispatch.delivery.time`, with latency histograms; pods log ECS JSON lines | None |
| Load tests | 15: nightly smoke, full load before release | `tools/k6/browse.js` (search, store, menu, quote; fails over 1 % errors or a 300 ms 95th percentile). Not wired into a nightly job, since GitHub runners are not the gate | Ordering journey under load |
| Charts in CI | 15 | PR checks lint both charts and validate every topology and a production-shaped render with kubeconform; a release pushes both charts to `oci://ghcr.io/ilyaslabs/charts`. Locally the gate is a server-side dry run against a real cluster | None |

## 28. M6 implementation notes

M6 is the [Operator handbook](handbook/README.md): nine launch guides, four for day-to-day running, knowing when to grow, ten scaling runbooks, troubleshooting and a glossary, each in the same five parts (what you need, steps, check, undo, if something goes wrong). With it, a manual QA suite in `qa/` (15 files, one per area, each case with the records to check in MongoDB, Redis, Kafka and Elasticsearch). Writing and testing the guides turned up gaps, fixed here:

| Area | Before | M6 | Follow-up |
|---|---|---|---|
| Staff accounts | `POST /api/v1/users` existed with no screen; owners and staff were made by hand | Back office **Users, New staff account** (Support, Finance, Admin only; the API refuses other roles with `USER_ROLES_INVALID`) and **Change password** in the account menu (`PUT /api/v1/me/password`) | Changing a password does not end the user's other sign-ins (a reset does) |
| Restore | `restore.sh` restored MongoDB and media and said search would rebuild | Also clears Redis (`FLUSHALL`) and deletes the `foodstack_search_stores_v*` index by name, so nothing newer than the backup survives; the server recreates and fills the index on start | None |
| Wrong personal-data key | A server started with another `FOODSTACK_PII_KEY` (a restore on a new server without the old key) read nobody's email and made a second first administrator | `BootstrapAdmin` first decrypts the first administrator's email and stops the start with a message naming the key | None |
| Sharding existing data (6.3) | `applyShardKeys` ran `shardCollection` only, which MongoDB refuses on a collection with rows and no index on the key; CI never saw it, since its collections start empty | The key's index is created first (an equal index under another name is accepted), so a replica set with data can become a sharded cluster; `ModuleMigrationsTest.shardsPopulatedCollection` | None |
| Stored image URLs | Brands, menu items and orders copy image URLs when written, so moving the photos (to S3 or a CDN) left old addresses | `tools/migrate/rewrite-media-urls.js` (mongosh) rewrites the old base to the new one in every `foodstack_*` collection, keeping BSON types; used by runbooks 40 and 49 | Store media ids only and build URLs on read |
| Deployment guide | Several steps named settings and screens that do not exist (`server.moduleDefaults`, `maintenance.reindex`, `install.sh --check`, a backup timer, Settings → Features) | Corrected against the code, and linked to the handbook | None |
| Price approval routing | `/api/v1/price-changes` (the operator's approve and reject) was missing from the path map the Helm chart builds its HTTPRoutes from, so split into services it answered 404; the monolith hid it | Routed to `store`; `ModuleRoutesTest` (in `integration-tests`) checks every controller path of every module against the map and the chart's copy of it | None |
| Integration test ports | The harness took free ports from the system's ephemeral range before starting the modules one by one, so an earlier module's outgoing connection could take a later module's port ("Address already in use", seen now and then) | Ports come from 20000 to 32000, below the ephemeral range | None |
| Settings in the journeys | `CatalogueScenario` turned on Arabic and searched at once; it passed only while no earlier journey had made the split services read the settings (they keep them 30 s) | Journeys that change settings wait out the 30 s when split into services, and not in the monolith | None |
| Publishing | Every merge to `main` published snapshots to Maven Central and `:edge` images | Nothing is published on a merge; only a GitHub release publishes (Maven Central, Docker Hub, GHCR, charts), ilyas 2026-11-08 | None |

Checked on a fresh single-server install in this environment: install, backup, restore (later data gone, Redis empty, search index recreated), upgrade to a repackaged release and rollback, the nightly cron line, a full restore onto a new install with the two keys copied, and a wrong key stopping the server. The Kubernetes runbooks are written against the chart and checked with `helm template`; the conversions that need a provider's console (MongoDB sharding, Redis Cluster, more Kafka partitions, a CDN) were not run here. The PRD exit check, someone without an operations background following every guide end to end, is still to do.

Two journeys join the integration tests, each run as a monolith and as one service per module: `CouponsAndRefundsScenario` (an operator coupon used once per customer and given back when an unpaid order is cancelled, then two partial refunds to the card, one charged to the store and one borne by the operator, with the branch's payout following) and `PricesAndSettlementScenario` (with price approval on, an owner's new price waits for approval and a rejected one never reaches customers; then finance settles the branch's finished day with a reference, refused for today, without a reference, for support, and a second time).
