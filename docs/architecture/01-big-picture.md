# 1. Big picture

[Back to index](README.md)

## 1.1 What runs where (single server, the unit deployer)

This is what `install.sh` starts today (`deploy/unit-deployer/src/main/dist/docker-compose.yml`). The Kubernetes chart is planned for M5 and does not exist yet.

```mermaid
flowchart LR
    subgraph Clients
        C[Customer browser]
        B[Back-office browser<br/>operator staff, store owners]
    end

    subgraph Server["One server (Docker Compose)"]
        P["Caddy<br/>TLS, routing by path,<br/>security headers, 25 MB body limit"]
        W["web<br/>Angular SSR on Node :4000"]
        BO["backoffice<br/>nginx with Angular build"]
        S["server<br/>Spring Boot, every module local :8080"]
        M[("MongoDB 8.2<br/>replica set")]
        R[("Redis 8.2")]
        K[("Kafka 4.1")]
        E[("Elasticsearch 9.4")]
        O[("RustFS<br/>S3 compatible")]
    end

    N["Nominatim<br/>(OpenStreetMap geocoding)"]
    T["Map tiles<br/>(OpenStreetMap by default)"]

    C --> P
    B --> P
    P -- "/api/*, /oauth2/*, /login, /logout,<br/>/.well-known/*, /userinfo, /connect/*" --> S
    P -- "/foodstack-public/*, /foodstack-private/*" --> O
    P -- "/admin*" --> BO
    P -- "everything else" --> W
    S --> M
    S --> R
    S --> K
    S --> E
    S --> O
    S -. "address search,<br/>cache miss only" .-> N
    C -. "map tiles" .-> T
    B -. "map tiles" .-> T
```

- The browser apps call the API on the same origin, so there is no CORS between the apps and the server.
- Images are served straight from object storage through Caddy, never through the Java server.
- The server's internal Module-Switch port is not used in this topology: every module is `local` in one JVM.

## 1.2 Modules, their data, and who calls whom

Solid arrows are **synchronous calls** through another module's `-api` interface (an in-process Java call in the monolith, an HTTP call to the internal port when modules are split). Dashed arrows are **Kafka events**.

```mermaid
flowchart TB
    subgraph identity
        ID[identity]
        IDdb[("foodstack_identity<br/>users, login_keys,<br/>authorizations")]
    end
    subgraph settings
        SE[settings]
        SEdb[("foodstack_settings<br/>settings")]
    end
    subgraph media
        ME[media]
        MEdb[("foodstack_media<br/>assets")]
    end
    subgraph geo
        GE[geo]
        GEdb[("foodstack_geo<br/>zones, settings<br/>(operator pricing)")]
    end
    subgraph store
        ST[store]
        STdb[("foodstack_store<br/>brands, stores, slugs,<br/>menu_sections, menu_items,<br/>item_overrides")]
    end
    subgraph search
        SR[search]
        ES[("Elasticsearch<br/>foodstack_search_stores<br/>(alias over _v1)")]
    end

    ID --- IDdb
    SE --- SEdb
    ME --- MEdb
    GE --- GEdb
    ST --- STdb
    SR --- ES

    SE -->|"MediaApi: claim / release logo"| ME
    ST -->|"SettingsApi: locale, currency"| SE
    ST -->|"MediaApi: claim / release images"| ME
    ST -->|"IdentityApi: grant / revoke store access, get user"| ID
    ST -->|"GeoApi: quote, delivery area"| GE
    SR -->|"StoreApi: listing, store ids"| ST
    SR -->|"GeoApi: delivery area"| GE

    ST -. "foodstack.store.events<br/>StoreChanged, BrandChanged, MenuChanged,<br/>ItemAvailabilityChanged, StoreAvailabilityChanged" .-> SR
    GE -. "foodstack.geo.events<br/>ZoneChanged, OperatorDeliveryPricingChanged" .-> SR
```

Every module also has its own `<module>_outbox`, `<module>_inbox` and Liquibase tracking collections in its database. `search` keeps no MongoDB database at all: its index can always be rebuilt from `store` and `geo`.

### Events with no consumer yet

These are published to Kafka today but nothing reads them. They are there for the modules that come in M2 and later (for example `notification`).

| Topic | Events |
|---|---|
| `foodstack.identity.events` | `UserRegistered`, `UserTokensRevoked` |
| `foodstack.settings.events` | `SettingsChanged` |
| `foodstack.media.events` | `MediaReady` |

## 1.3 Inside one module

Every module has the same four parts. Only `-impl` and `-web` are entry points; both call the same `-core` services, so no rule is written twice.

```mermaid
flowchart LR
    subgraph other["Another module"]
        X["its -core service"]
    end
    subgraph m["Module (for example store)"]
        API["store-api<br/>StoreApi interface, records, events"]
        IMPL["store-impl<br/>StoreApiImpl"]
        WEB["store-web<br/>StoresController, BrandsController, DTOs"]
        CORE["store-core<br/>StoreService, MenuService,<br/>repositories, outbox, Liquibase"]
    end
    HTTP["REST client<br/>(browser, mobile app)"] --> WEB
    X -->|"calls the interface"| API
    API -.->|"implemented by"| IMPL
    IMPL --> CORE
    WEB --> CORE
    CORE --> DB[("foodstack_store")]
```

- Permission checks live in `-web` (`@PreAuthorize`, `CurrentUser.requireStoreAccess`). `-impl` runs none; see [Code vs design](05-code-vs-design.md).
- Where a module is `http` or `off`, its `-core`, `-impl` and `-web` beans are not created; another module that needs it gets a Module-Switch HTTP client for its `-api` instead.
