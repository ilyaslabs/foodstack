# 5. Code vs design

[Back to index](README.md)

Found while tracing the flows on these pages against the [TDD](../TDD.md), first after M1, again after M2, after M3, after the scale-out work (PR #7) and after M4. Choices already recorded in TDD sections 21 to 26 are not repeated here unless the code still differs from what those sections say.

## 5.1 Differences that change behaviour or reliability

| # | Area | TDD says | Code does | Effect | Where | Status |
|---|---|---|---|---|---|---|
| G1 | Failed event handling | 7.1: 3 attempts with backoff, then the `.dlt` topic; the admin UI lists DLT messages for replay | 3 attempts with a growing pause, then `<topic>.dlt` with the error in the headers. No screen to list or replay dead letters yet | A failing event is kept, not lost; replay is by hand (Kafka tools) | `platform-kafka` `DeadLetters` | **Fixed in M2**, except the replay screen (M5, with the admin tools) |
| G2 | Search repair | A periodic or manual rebuild repairs anything missed | One replica re-reads every branch once a day (`foodstack.search.rebuild-interval`), plus `POST /api/v1/search/reindex` | A missed update heals within a day | `SearchIndexBootstrap`, `ScheduledRebuildLock` | **Fixed in M2** |
| G3 | "Open now first" ranking | 22: open now first, then text relevance, then distance | The index stores each branch's opening windows; Elasticsearch sorts open branches first across all pages | Page 1 always shows the open restaurants first | `ElasticsearchStoreSearchEngine`, `OpenWindows` (mapping v2) | **Fixed in M2** |
| G4 | Calls between modules with no user | 9.1: background work uses a client-credentials token; the called module checks the same permissions | Every `-impl` checks the caller with `ModuleAccess`; the forwarded user token is authenticated by the called module; background work runs through `ServiceCalls` with a service token (client `foodstack-service`) | A module can never do more than the user behind the call | every `*-impl`, `platform-security` | **Fixed in M2** |
| G5 | Menu cache vs search index | 5.7: menu cache evicted on `MenuChanged` / `ItemAvailabilityChanged` | The menu cache is now keyed by a generation number that `MenuService` raises after the commit (this fixed a race where a reader wrote an old menu back). The event can still reach search a moment before the generation is raised | Rare: search can index the old menu until the next change or the daily re-read (G2) | `MenuService`, `MenuCache` | Open, much smaller since G2 |
| G6 | Delivery quote caching | 12: quotes are cached briefly | Every cart read and checkout quote prices the lines against the cached menu, but reads the branch, zones or operator pricing from MongoDB, and the time zone from `settings` when peak hours are set | One indexed read per quote (plus a cached settings read with peak hours); fine at today's volume. Revisit with load tests | `QuoteService`, `CheckoutService` | **Fixed in PR #7**: zones and operator pricing are cached in Redis (`GeoCache`, 10 min, generation keys); services that reach `settings` over the network keep its answers 30 s |
| G7 | Event tracing | 7.1: envelope carries `traceparent`; decision 12: OpenTelemetry | No tracing library, and the outbox stores no `traceparent` | A flow across Kafka is followed by order id in the logs, not by a trace | `OutboxWriter`, server dependencies | **Fixed in PR #7**: the outbox stores `traceparent`, the relay sends it as a Kafka header, listeners continue the trace; OTLP export by config. The observability chart stays M5 |
| G8 | Accept-timeout and payment-expiry sweeps | Not specified | Run on every replica every 15 s and every minute; each change is a conditional update, so two replicas never reject or expire the same order twice | Extra reads of `order_active` and `payment_intents` with many replicas; no wrong results | `OrderConfiguration`, `PaymentConfiguration` (`PeriodicTask`) | **Changed in PR #7**: the accept-timeout sweep runs over each replica's work buckets (`PartitionedTask`), so replicas no longer read the same rows; the payment-expiry sweep stays on every replica (rare, conditional) |
| G9 | Live updates after a reconnect | 8.3: clients reconnect with `Last-Event-ID` | No event ids; a reconnecting client reloads the order or the board once, then follows new messages | Same result: the screen is never stale. Simpler, because a message carries no data to replay | `OrderStreamsController`, `live-stream.ts` | By design |
| G10 | Dispatch score | 5.6: weights in settings | Fixed weights in `DispatchService.score` | Operators cannot tune ranking yet | `DispatchService` | By design for v1 (TDD 24) |
| G11 | Delivery code in `OrderAccepted` | 9: PII encrypted in events | Was: the 4-digit code travelled in clear inside `OrderDelivery`. Now the order keeps it encrypted and events carry only the cipher | None left | `OrderPlacementService.encrypt`, `DispatchService.decrypt` | Fixed in the M3 follow-up |
| G12 | Push delivery log | 5.2 notification: delivery log | Emails are logged with retries; push is sent once with no log entry | A lost push is not visible to support; the in-app message is always there | `PushService` | **Partly fixed in PR #7**: push is queued in `notification_deliveries` (channel `PUSH`) with two quick retries; the admin log still lists emails only |
| G13 | Sign-in | 9.1 (before PR #7): hosted login page, Authorization Code + PKCE, server session | Stateless auth API (`/api/v1/auth/*`), in-app sign-in screens, rotating refresh tokens | Any replica answers any sign-in; no session to share | `AuthController`, `SessionService`, `auth` library | **Changed in PR #7** (ilyas, 2026-11-03); TDD 9.1 rewritten |
| G14 | Merchant routing | 5.4: `order` calls `payment.resolveMerchant` | Same call (`MerchantAccounts.resolve`): branch account, else brand account, else the operator's, and only while finance allows the branch and the ledger has not suspended it | As designed; see [9.3](09-money-and-trust-flows.md#93-stores-that-collect-card-payments-themselves) | `MerchantAccounts`, `OrderPlacementService` | Built in M4 |
| G15 | Embedded card fields and saved cards | 5.4: per gateway | Operator's merchant account only; a branch's own account always uses the hosted page | Card numbers never reach FoodStack (PCI SAQ A, ilyas 2026-11-07) | `PaymentService.checkout`, `pay-page.ts` | By design for M4 (TDD 26) |
| G16 | Data export | PRD ID-7: one export | The browser joins four exports, one per module that holds customer data | No module reads another's data | `account-page.ts`, each module's `/mine/export` | By design (TDD 26) |
| G17 | Deletion grace | PRD ID-7 | 14 days (`foodstack.identity.deletion-grace`), sweep every 30 min; a sign-in during the grace period cancels, a sign-in after it is refused | Erasure happens up to 30 min after the grace ends | `AccountDeletion`, `UserService.cancelDeletion` | Built in M4 |

## 5.2 Places where the TDD text is out of date (code is fine)

| # | TDD section | Says | Reality | Suggested TDD change |
|---|---|---|---|---|
| D1 | 5.2 module catalogue, "Sync dependencies" column | `store` → `geo`, `settings`; `search` → `geo`; `cart` → `store`, `geo`, `promotion`; `order` → `cart`, `store`, `promotion`, `payment`, `geo`; `notification` → `identity` | `store` → `geo`, `settings`, `media`, `identity`; `search` → `store`, `geo`; `settings` → `media`; `cart` → `store`, `geo`, `promotion`, `settings`; `order` → `cart`, `promotion`, `payment`, `identity`, `settings`; `payment` → `settings`; `notification` → `identity`, `order`, `settings` | Update the column; see [1.2](01-big-picture.md#12-modules-their-data-and-who-calls-whom). `order` no longer calls `store` or `geo`: it gets both through `cart` (TDD 23) |
| D2 | 10, Spring Cache row | Spring Cache with keys `foodstack:<module>:cache:<name>:<key>` | Hand-written read-through caches. Settings and geocoding follow the key pattern; the menu cache uses `foodstack:store:menu:<storeId>:<generation>` | Describe the real caches and keys; see [2.4](02-platform-mechanics.md#24-what-is-in-redis) |
| D3 | 10, last paragraph | "Redis Cluster is supported because every key family has its own hash tag" | No key uses a `{hash tag}`; every operation touches one key, so Cluster works anyway, and live messages switch to sharded pub/sub on a cluster | Updated in PR #7 (TDD 10) |
| D4 | 7.2 | Every consumer uses the inbox | Consumers that change counters or state once per event use it (promotion, order's payment listener); naturally idempotent ones do not (search, order projections, notification keyed by event id, payment refunds keyed by event id) | Say so; see [2.3](02-platform-mechanics.md#23-events-outbox-relay-kafka-consumer) |
| D5 | 9.1 revocation | "short in-memory cache" | 5 seconds (`foodstack.security.revocation-cache-ttl`), at most 100,000 users | Updated in PR #7 (TDD 9.1) |
| D6 | 5.3 order placement flow | `order` calls `cart.checkoutSnapshot`, then `store`, `geo` and `promotion` itself and computes the totals | `order` calls `CartApi.checkout` once; `cart` prices everything, and the same code shows the customer the quote | Replace the flow with [6.3](06-ordering-flows.md#63-place-an-order-cash) |
| D7 | 5.2 events | `order` publishes 8 events; `payment` 3; `identity` 4 | `order` publishes one event per step (12, adding `OrderPreparing`, `OrderCollected`, `OrderRejected`, `OrderFailed`); `payment` adds `RefundFailed`; `identity` adds `EmailCodeIssued` (M4 adds `AccountDeletionScheduled` and `UserDeleted`; `RoleChanged` is not built) | Update the Publishes column |
| D8 | 8.1 idempotency | `Idempotency-Key` cached 24 h in Redis on order placement, payments and refunds | Order placement only, kept in MongoDB (`order_placement_keys`) inside the order's transaction. Payments are one per order (`_id` = order id) and refunds are keyed by the event id, so neither needs a header | Describe the per-case keys |
| D9 | 8.1 rate limiting, 10 | Redis token bucket | Fixed window counter (`foodstack:<module>:rl:<scope>:<id>:<window>`) | Say fixed window; a token bucket only if bursts become a problem |
| D10 | 10 login codes | `foodstack:identity:otp:<emailHash>` | `foodstack:identity:code:<purpose>:<userId>` (hash of the code and tries) plus a per-email rate limit | Update the row |
| D11 | 5.2 REST prefixes | `promotion`: `/api/v1/coupons`, `/api/v1/offers` | `/api/v1/promotions` (coupons are promotions with a code; offers without a code come with home sections) | Update the prefix |
| D12 | 5.2 consumes | `cart` consumes `store.*` to invalidate | `cart` keeps no prices, so it needs no events | Remove the entry |

## 5.3 Designed but not built yet (by plan)

Listed so nobody looks for them in the code. Milestones are from the [PRD release plan](../PRD.md#7-release-plan).

| Area | TDD | Planned |
|---|---|---|
| Kubernetes Helm chart, Gateway API routes, edge JWT check, Istio mTLS, `AuthorizationPolicy` | 9.1, 18 | M5 |
| Edge rate limiting on Kubernetes (Envoy rate-limit service) | 8.1, 18.3 | M5, with the Helm chart (a single server has no edge limits: ilyas, 2026-11-07; the app's own limits apply) |
| Batching two orders to one rider | PRD DR-9 | Later ("Could") |
| Social login, TOTP | 9.1 | M5 (email codes are built) |
| Operator handbook: step-by-step setup and scaling runbooks for non-experts | Deployment guide | M6 |
| Generated OpenAPI and Angular client | 8.1 | Recorded in TDD 21 |
| Observability chart (Collector, Tempo, Grafana) | 18.5 | M5 (tracing itself is built, see G7) |
| Dead-letter screen and replay | 7.1 | M5 (see G1) |
