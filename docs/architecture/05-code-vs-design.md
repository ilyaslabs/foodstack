# 5. Code vs design

[Back to index](README.md)

Found while tracing the flows on these pages against the [TDD](../TDD.md) after M1. Items already recorded in TDD sections 21 and 22 are not repeated here unless the code still differs from what those sections say.

## 5.1 Differences that change behaviour or reliability

These need a decision: fix the code, or change the design.

| # | Area | TDD says | Code does | Effect | Where |
|---|---|---|---|---|---|
| G1 | Failed event handling | 7.1: 3 attempts with backoff, then the `.dlt` topic; the admin UI lists DLT messages for replay | No Kafka error handler is configured, so Spring Kafka's default runs: 10 attempts with no pause, then the record is logged and skipped. The `.dlt` topics are created but nothing writes to them | If Elasticsearch or the store module is down for a few seconds, the index update for that branch is lost until someone runs a reindex | `platform-kafka` (no `CommonErrorHandler` bean) |
| G2 | Search repair | `CatalogueEventsListener` says "the periodic or manual rebuild repairs anything missed" | There is no periodic rebuild. The index is rebuilt only when it did not exist at start-up, or on `POST /api/v1/search/reindex` | Together with G1, a missed update stays wrong until a manual reindex | `search-core` `SearchIndexBootstrap`, `CatalogueEventsListener` |
| G3 | "Open now first" ranking | 22: open now first, then text relevance, then distance | Elasticsearch sorts by relevance and distance and pages the result; the open-before-closed sort happens afterwards, **inside one page only** | Page 1 can show closed restaurants while open ones sit on page 2 | `StoreSearchService.search` |
| G4 | Calls between modules with no user | 9.1: background work calls other modules with a short-lived client-credentials token; the called module validates the forwarded JWT and applies the same permissions | The search Kafka listener calls `StoreApi` and `GeoApi` with no token. No `-impl` class checks permissions; permission checks exist only in `-web` controllers. No client-credentials client is registered | When split, any caller holding the Module-Switch internal token can call any module API with full rights. Fine inside a trusted network, but weaker than the design's "a module can never do more than the user" | every `*-impl`, `identity` client registration |
| G5 | Menu cache vs search index | 5.7: menu cache evicted on `MenuChanged` / `ItemAvailabilityChanged` | Evicted directly by `MenuService` right after the transaction commits, before the event could be consumed in almost every case. There is a small window where the relay sends the event before the eviction runs; search would then index the old cached menu | Rare: dish search can show a dish as available (or miss a new dish) for up to 10 minutes, until the next change to that branch | `MenuService.inMenuTransaction` / `setSoldOut` |
| G6 | Delivery quote caching | 12: quotes are cached briefly | Every quote reads the branch, its zones or the operator pricing from MongoDB | Fine at today's volume; matters once the cart and checkout quote on every change (M2) | `QuoteService`, `StoreService.quote` |
| G7 | Event tracing | 7.1: envelope carries `traceparent`; decision 12: OpenTelemetry | No tracing library is on the classpath and the outbox never stores a `traceparent`, so events cannot be followed across modules | Harder to debug a flow that crosses Kafka | `OutboxWriter`, server dependencies |

## 5.2 Places where the TDD text is out of date (code is fine)

| # | TDD section | Says | Reality | Suggested TDD change |
|---|---|---|---|---|
| D1 | 5.2 module catalogue, "Sync dependencies" column | `store` → `geo`, `settings`; `search` → `geo` | `store` → `geo`, `settings`, `media`, `identity`; `search` → `store`, `geo`; `settings` → `media` | Update the column and the "synchronous dependency graph" sentence; link [1.2](01-big-picture.md#12-modules-their-data-and-who-calls-whom) |
| D2 | 10, Spring Cache row | Spring Cache with keys `foodstack:<module>:cache:<name>:<key>` | Hand-written read-through caches on `StringRedisTemplate`. Settings and geocoding follow the key pattern; the menu cache uses `foodstack:store:menu:<storeId>` (as written in 5.7) | Describe the real caches and keys; see [2.4](02-platform-mechanics.md#24-what-is-in-redis) |
| D3 | 10, last paragraph | "Redis Cluster is supported because every key family has its own hash tag" | No key uses a `{hash tag}`. Every operation today touches a single key, so Cluster works anyway; hash tags only matter once a multi-key operation is added | Reword to "every operation uses one key; add hash tags with the first multi-key operation" |
| D4 | 7.2 | Consumers use the inbox for effectively-once processing | `InboxGuard` exists but no consumer uses it; the only consumer (search) is idempotent by design | Say the inbox is used by consumers whose handler is not naturally idempotent (M2 onwards) |
| D5 | 9.1 revocation | "short in-memory cache" | 5 seconds (`foodstack.security.revocation-cache-ttl`) | State the number |

## 5.3 Designed but not built yet (by plan)

Listed so nobody looks for them in the code. Milestones are from the [PRD release plan](../PRD.md#7-release-plan).

| Area | TDD | Planned |
|---|---|---|
| Kubernetes Helm chart, Gateway API routes, edge JWT check, Istio mTLS, `AuthorizationPolicy` | 9.1, 18 | M5 |
| Edge and per-user rate limiting | 8.1, 10 | Recorded in TDD 21; with the first public write endpoints |
| Idempotency keys, SSE, rider GEO, dispatch locks in Redis | 8, 10 | M2 and M3 |
| Login codes, social login, TOTP | 9.1 | M2 and M5 (TDD 21) |
| Login session in Redis (needed for more than one identity replica) | 21 | Before running identity with several replicas |
| Generated OpenAPI and Angular client | 8.1 | Recorded in TDD 21 |
| OpenTelemetry, observability chart | 18.5 | M5 (see also G7) |
| `RoutingProvider` (OSRM) instead of straight line × 1.3 | 12 | M3 (TDD 22) |
