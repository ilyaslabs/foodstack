# 2. Platform mechanics

[Back to index](README.md)

The machinery every module shares. Code lives in `platform/` (`platform-security`, `platform-mongo`, `platform-kafka`, `platform-web`).

## 2.1 What happens to every request

Spring Security picks the first filter chain whose path matches. The chains, in order:

| Order | Chain | Paths | Rule |
|---|---|---|---|
| 1 | Authorization server (`identity-web`) | `/oauth2/**`, `/.well-known/**`, `/userinfo`, `/connect/logout` | Spring Authorization Server endpoints; a browser without a login session is sent to `/login` |
| 2 | Login (`identity-web`) | `/login`, `/login/**`, `/logout` | Form login page, open to all; keeps a server-side session |
| 10 | API (`platform-security`) | `/api/**` | Stateless JWT resource server. Endpoints a module declares as `PublicEndpoints` are open; everything else needs a valid token |
| last | Default | everything else | Only `/actuator/health/**`, `/actuator/info` and `/error` are open; the rest is denied |

```mermaid
sequenceDiagram
    autonumber
    participant Br as Browser
    participant Cd as Caddy
    participant F as API filter chain<br/>(platform-security)
    participant Rd as Redis
    participant C as Controller (-web)
    participant Co as Service (-core)

    Br->>Cd: GET /api/v1/stores/{id}/details<br/>Authorization: Bearer JWT
    Cd->>F: same request (TLS ended at Caddy)
    alt path is a declared public endpoint
        F->>C: no token needed
    else token required
        F->>F: verify ES256 signature with identity's keys<br/>(in memory, no call to identity)
        F->>F: check issuer, expiry, not-before
        F->>Rd: GET foodstack:identity:revoked:{userId}<br/>(skipped if cached in this JVM < 5 s)
        Rd-->>F: minimum token version, or nothing
        alt token's tv claim is older
            F-->>Br: 401 invalid_token (revoked)
        end
        F->>F: authorities = perms claim + ROLE_{role}
        F->>C: authenticated request
    end
    C->>C: @PreAuthorize permission check,<br/>CurrentUser.requireStoreAccess(storeId) from storeIds / brandIds claims
    C->>Co: call the application service
    Co-->>C: result, or a ModuleException
    C-->>Br: JSON, or application/problem+json with code (e.g. STORE_ACCESS_DENIED)
```

- **Where the signing keys come from.** In the JVM that runs `identity`, the decoder reads the keys straight from `identity`'s `JWKSource` bean. In any other JVM (split topology) it downloads them once from `/oauth2/jwks` and caches them.
- **Revocation.** `identity` raises the user's `tokenVersion` (for example on "sign out everywhere"), writes the new minimum to Redis and also publishes `UserTokensRevoked`. Other modules read Redis, not the event. Each JVM caches the value for 5 seconds (`foodstack.security.revocation-cache-ttl`), so a revoked token can still pass for up to 5 seconds on a JVM that just checked it.
- **Public API endpoints today** (each declared by its module's `-web` configuration): `GET /api/v1/search/stores`; `GET /api/v1/stores/{key}`, `/{key}/menu`, `/{key}/delivery-quote`; `GET /api/v1/settings/branding`, `/locale`; `GET /api/v1/geo/addresses`, `/addresses/reverse`, `/addresses/availability`; `POST /api/v1/auth/register`. Everything else under `/api/**` needs a token.

## 2.2 Each module gets its own database

```mermaid
flowchart LR
    subgraph JVM["foodstack-server JVM"]
        F["ModuleMongoFactory<br/>(platform-mongo)"]
        F -->|"create(store)"| MS["ModuleMongo store<br/>MongoTemplate + transaction manager"]
        F -->|"create(geo)"| MG["ModuleMongo geo"]
        MS --> L1["ModuleMigrations store<br/>Liquibase db/changelog/store/…"]
        MG --> L2["ModuleMigrations geo"]
    end
    MS -->|"shared client"| DB1[("foodstack_store")]
    MG -->|"or own client if<br/>foodstack.modules.geo.mongodb.uri is set"| DB2[("foodstack_geo")]
```

- One MongoDB cluster, one database per module (`foodstack_<module>`), every collection prefixed with the module name.
- A module's Liquibase changelog runs at start-up, before the app takes traffic, only in the JVM where that module is `local`. Tracking collections are `<module>_databasechangelog` and `<module>_databasechangeloglock`.
- With `foodstack.mongodb.sharding.enabled=true`, the declared shard keys are applied after migration (CI runs this against a two-shard cluster).
- `--foodstack.migrate-only=true` runs every module's migrations and exits (used before an upgrade).

## 2.3 Events: outbox, relay, Kafka, consumer

```mermaid
sequenceDiagram
    autonumber
    participant Svc as store-core service
    participant Mo as MongoDB<br/>foodstack_store
    participant Rl as OutboxRelay (store)<br/>virtual thread
    participant Rd as Redis
    participant Kf as Kafka<br/>foodstack.store.events
    participant Ls as search listener<br/>group foodstack-search

    Note over Svc,Mo: One MongoDB transaction
    Svc->>Mo: save branch (store_stores)
    Svc->>Mo: insert store_outbox {type, subject=storeId, data, status NEW}
    Note over Svc,Mo: commit: both rows or neither

    loop every 100 ms (sooner when a full batch was found)
        Rl->>Rd: acquire or renew foodstack:store:outbox:leader (10 s lease)
        alt this replica is the leader
            Rl->>Mo: find status NEW, order by _id, limit 500
            Rl->>Kf: send each row, key = subject,<br/>value = CloudEvents JSON (acks=all, idempotent)
            Kf-->>Rl: acknowledged
            Rl->>Mo: mark sent rows SENT (TTL index drops them after 7 days)
        end
    end

    Kf->>Ls: deliver event
    Ls->>Ls: route by type (StoreChanged, ZoneChanged, …)
    Note over Ls: search re-reads the branch instead of trusting the event body,<br/>so duplicates and reordering are harmless (flow 4.2)
```

- **Topics:** one per publishing module, `foodstack.<module>.events`, 24 partitions, 7-day retention, plus `.dlt` (30 days). They are created at start-up by each module's `KafkaAdmin.NewTopics` bean.
- **Ordering:** the key is the aggregate id, so events of one branch stay in order. If a send fails, only rows before the failure are marked sent; the failed row and the ones after it go again on the next poll.
- **Delivery guarantee:** at least once. The only consumer today (search) is idempotent by design, so it does not use the inbox collection.
- **Leader lock:** one relay per module across all replicas. A Lua script takes the lock if free or renews it if this replica already holds it.
- **When a consumer fails:** no error handler is configured, so Spring Kafka's default applies: 10 attempts with no pause, then the record is logged and skipped. Nothing is written to the `.dlt` topic yet; see [Code vs design](05-code-vs-design.md).

## 2.4 What is in Redis

Every key starts with the namespace `foodstack`. Redis holds caches and coordination only; losing it loses no business data.

| Key | Written by | Value | Lifetime | Evicted when |
|---|---|---|---|---|
| `foodstack:settings:cache:branding`, `…:locale` | `settings` | Branding or locale JSON | 10 min (`foodstack.settings.cache-ttl`) | `settings` saves a change |
| `foodstack:store:menu:{storeId}` | `store` | The branch's effective menu (brand menu filtered by branch, with sold-out marks) | 10 min (`foodstack.store.menu-cache-ttl`) | A menu edit (all branches of the brand) or a sold-out change (that branch), right after the transaction commits |
| `foodstack:geo:cache:geocode:q:{lang}:{text}` and `…:r:{lang}:{lat}:{lng}` | `geo` | Address search or reverse geocode results (point rounded to about 11 m) | 7 days (`foodstack.geo.geocoding.cache-ttl`) | Never; expires |
| `foodstack:identity:revoked:{userId}` | `identity` | Lowest token version still valid | No expiry | Overwritten on the next revocation |
| `foodstack:{module}:outbox:leader` | each publishing module | Id of the replica that relays the outbox | 10 s, renewed every poll | Released on shutdown |

Not in Redis yet (designed in TDD 10, not built): idempotency keys, rate limits, login codes, rider locations, dispatch locks, SSE fan-out.

## 2.5 Module to module: monolith vs split

The same code runs in both shapes; only configuration (`module-switch.modules.<m>.mode`) changes.

```mermaid
sequenceDiagram
    autonumber
    participant SS as store-core<br/>StoreService
    participant GA as GeoApi<br/>(interface)
    participant GI as geo-impl<br/>GeoApiImpl
    participant GC as geo-core<br/>QuoteService

    Note over SS,GC: Monolith: geo is local
    SS->>GA: quote(request)
    GA->>GI: plain Java call, same thread
    GI->>GC: quote(request)
    GC-->>SS: DeliveryQuoteView

    Note over SS,GC: Split: geo runs in another Deployment
    SS->>GA: quote(request)
    GA->>GI: HTTP POST {geo-url}/geo/GeoApi/quote<br/>headers: X-Module-Switch-Token, Authorization, Accept-Language
    GI->>GC: quote(request)
    GC-->>GI: DeliveryQuoteView
    GI-->>SS: JSON, mapped back to the record<br/>(errors come back as the same ModuleException)
```

- `Authorization` and `Accept-Language` are forwarded (`module-switch.propagate-headers`).
- The integration tests run every journey twice: once as a monolith and once with one application context per module talking over HTTP (`integration-tests`, `Topology.MICROSERVICES`).
