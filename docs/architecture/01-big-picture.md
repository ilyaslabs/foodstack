# 1. Big picture

[Back to index](README.md)

## 1.0 What each service needs

Start here if you are setting FoodStack up. Each box in the middle is one module. On one server
they all run in one process; split up, each one (or each group) is its own service. Either way,
each module needs the same things. Thick arrows are required, dashed arrows are optional.

```mermaid
flowchart TB
    subgraph apps["Browser apps"]
        WEB["web<br/>customer app"]
        BO["backoffice<br/>staff, stores, riders"]
    end
    TILES["Map tiles<br/>{z}/{x}/{y} provider"]

    subgraph services["FoodStack modules: one process, or one service each"]
        subgraph cat["Accounts and catalogue"]
            direction TB
            identity
            settings
            media
            geo
            store
            search
        end
        subgraph ord["Ordering"]
            direction TB
            promotion
            cart
            payment
            order
            review
        end
        subgraph del["Delivery, messages, money"]
            direction TB
            rider
            dispatch
            notification
            ledger
        end
    end

    subgraph shared["Used by every module"]
        direction LR
        M[("MongoDB<br/>one database per module")]
        R[("Redis")]
        K[("Kafka")]
    end

    SOCIAL["Google, Apple<br/>sign-in"]
    S3[("Your S3 buckets")]
    GEO["Address search<br/>Google or Nominatim"]
    ROUTE["Road distances<br/>Google or OSRM"]
    ES[("Elasticsearch")]
    STRIPE["Stripe<br/>card payments"]
    SMTP["SMTP server<br/>emails"]
    FCM["Firebase<br/>push"]

    apps ==> TILES
    apps ==> S3
    services ==> shared
    identity -.-> SOCIAL
    media ==> S3
    geo ==> GEO
    geo -.-> ROUTE
    search ==> ES
    payment -.-> STRIPE
    notification -.-> SMTP
    notification -.-> FCM
```

| Module | Its own database | Also needs | Outside services | Calls these modules |
|---|---|---|---|---|
| `identity` | `foodstack_identity` | signing key, first admin | Google and Apple sign-in (optional) | |
| `settings` | `foodstack_settings` | | | media |
| `media` | `foodstack_media` | | **S3 buckets (required)** | |
| `geo` | `foodstack_geo` | | **Address search (required)**; road distances (optional) | settings |
| `store` | `foodstack_store` | | | geo, identity, media, settings |
| `search` | none | **Elasticsearch** | | geo, review, store |
| `promotion` | `foodstack_promotion` | | | |
| `cart` | `foodstack_cart` | | | geo, payment, promotion, settings, store |
| `payment` | `foodstack_payment` | | Stripe (optional) | settings |
| `order` | `foodstack_order` | | | cart, identity, payment, promotion, settings |
| `rider` | `foodstack_rider` | | | geo, identity, media, settings |
| `dispatch` | `foodstack_dispatch` | | | geo, rider, settings |
| `notification` | `foodstack_notification` | | SMTP (optional), Firebase (optional) | identity, order, settings |
| `ledger` | `foodstack_ledger` | | | settings |
| `review` | `foodstack_review` | | | identity, media |

- **Every module** also needs MongoDB, Redis and Kafka, the public API address (the token issuer),
  and the personal-data key: each one checks that key on start.
- **When modules are split**, every service also needs the internal token and the service client
  secret, and every service except `identity` needs identity's address for the token keys.
- **The two apps** need the map tiles; the back office also needs your bucket's address to show
  and upload photos. Browsers load photos straight from the bucket, never through the server.
- Ready-made settings, one file per module, with every value above: [deploy/services](../../deploy/services/README.md).
  On one server they are the lines of `.env` ([deployment guide](../deployment-guide.md)); on
  Kubernetes, the chart's values ([Kubernetes guide](../kubernetes-guide.md)).

"Calls these modules" means the module asks them for answers while it works: a Java call in one
process, an HTTP call on port 8081 when they are split, so those services must be reachable from
it. Everything else between modules travels as Kafka events ([1.2](#12-modules-their-data-and-who-calls-whom)).

## 1.1 What runs where (single server, the unit deployer)

This is what `install.sh` starts today (`deploy/unit-deployer/src/main/dist/docker-compose.yml`). The Kubernetes chart (M5) is drawn in [10.1](10-kubernetes.md#101-what-the-chart-installs).

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
    end

    O[("Operator's S3 buckets<br/>Amazon S3, R2, B2")]
    N["Address search<br/>Google or Nominatim-compatible"]
    RT["Road distances (optional)<br/>Google Routes or OSRM"]
    T["Map tiles<br/>{z}/{x}/{y} provider"]

    C --> P
    B --> P
    P -- "/api/*, /oauth2/*, /.well-known/*" --> S
    P -- "/admin*" --> BO
    P -- "everything else" --> W
    S --> M
    S --> R
    S --> K
    S --> E
    S -- "StorageCheck on start,<br/>writes, signed links" --> O
    C -- "images, signed uploads" --> O
    B -- "images, signed uploads" --> O
    S -. "address search,<br/>cache miss only" .-> N
    S -. "routes" .-> RT
    C -. "map tiles" .-> T
    B -. "map tiles" .-> T
```

- The browser apps call the API on the same origin, so there is no CORS between the apps and the server.
- Images are served straight from the operator's public bucket (or a CDN in front of it), never through the Java server; browsers upload with presigned PUT links, allowed by the bucket's CORS rule. The server refuses to start until `StorageCheck` passes. A `--local` trial instead runs a local S3 stand-in behind Caddy (`docker-compose.trial-storage.yml`) and OpenStreetMap's public maps.
- The server's internal Module-Switch port is not used in this topology: every module is `local` in one JVM.
- The same code runs on many machines: see [8. How FoodStack scales out](08-scaling.md) for the scaled-out picture and the growth stages.

## 1.2 Modules, their data, and who calls whom

Solid arrows are **synchronous calls** through another module's `-api` interface (an in-process Java call in the monolith, an HTTP call to the internal port when modules are split). Dashed arrows are **Kafka events**.

```mermaid
flowchart TB
    subgraph identity
        ID[identity]
        IDdb[("foodstack_identity<br/>users, login_keys,<br/>refresh_tokens")]
    end
    subgraph settings
        SE[settings]
        SEdb[("foodstack_settings<br/>settings (branding, locale,<br/>ordering)")]
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
        ES[("Elasticsearch<br/>foodstack_search_stores<br/>(alias over _v2)")]
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

**Ordering modules (M2).** The catalogue modules they call are drawn as plain boxes; their databases are in the diagram above.

```mermaid
flowchart TB
    subgraph promotion
        PR[promotion]
        PRdb[("foodstack_promotion<br/>promotions, codes, redemptions,<br/>customer_usage, customers")]
    end
    subgraph cart
        CA[cart]
        CAdb[("foodstack_cart<br/>carts")]
    end
    subgraph payment
        PA[payment]
        PAdb[("foodstack_payment<br/>intents")]
    end
    subgraph order
        OR[order]
        ORdb[("foodstack_order<br/>orders, active, by_customer,<br/>by_store, placement_keys")]
    end
    subgraph notification
        NO[notification]
        NOdb[("foodstack_notification<br/>inbox, deliveries,<br/>preferences")]
    end
    ST[store]
    GE[geo]
    SE[settings]
    ID[identity]
    GW(["Card gateway<br/>Stripe Checkout or sandbox"])
    SMTP(["SMTP server"])

    PR --- PRdb
    CA --- CAdb
    PA --- PAdb
    OR --- ORdb
    NO --- NOdb

    OR -->|"CartApi: checkout, clear"| CA
    OR -->|"PromotionApi: reserve, release"| PR
    OR -->|"PaymentApi: startCard"| PA
    OR -->|"IdentityApi, SettingsApi"| ID
    CA -->|"StoreApi: validateOrder"| ST
    CA -->|"GeoApi: quote"| GE
    CA -->|"PromotionApi: evaluate"| PR
    CA -->|"SettingsApi: ordering"| SE
    PA -->|"SettingsApi: ordering"| SE
    PA -->|"hosted page, refunds"| GW
    GW -->|"webhook"| PA
    NO -->|"IdentityApi, OrderApi, SettingsApi"| OR
    NO -->|"emails"| SMTP

    OR -. "foodstack.order.events<br/>OrderConfirmed, OrderCancelled,<br/>OrderRejected, OrderFailed" .-> PR
    OR -. "OrderCancelled, OrderRejected,<br/>OrderRefunded" .-> PA
    PA -. "foodstack.payment.events<br/>PaymentSucceeded, PaymentFailed,<br/>RefundSucceeded, RefundFailed" .-> OR
    OR -. "order steps" .-> NO
    PA -. "RefundSucceeded" .-> NO
    ID -. "foodstack.identity.events<br/>UserRegistered, EmailCodeIssued" .-> NO
```

Every module also has its own `<module>_outbox`, `<module>_inbox` and Liquibase tracking collections in its database, and every collection name starts with the module name (`order_orders`, `cart_carts`). `search` keeps no MongoDB database at all: its index can always be rebuilt from `store` and `geo`. `order` also reads its own events to keep the order lists (`order_by_customer`, `order_by_store`) up to date. `order` calls `SettingsApi` as well as `IdentityApi`, and `notification` calls all three of `IdentityApi`, `OrderApi` and `SettingsApi`; each is drawn as one arrow to keep the diagram readable. The flows of the ordering modules are on [6. Ordering flows](06-ordering-flows.md).

**Delivery modules (M3).** `rider` keeps rider accounts, where riders are and what they earned; `dispatch` keeps one job per delivery order and finds riders. The flows are on [7. Delivery flows](07-delivery-flows.md).

```mermaid
flowchart TB
    subgraph rider
        RI[rider]
        RIdb[("foodstack_rider<br/>riders, assignments,<br/>locations, earnings")]
    end
    subgraph dispatch
        DI[dispatch]
        DIdb[("foodstack_dispatch<br/>jobs, active")]
    end
    RD[("Redis<br/>rider GEO set and positions,<br/>offer locks, live channels")]
    OR[order]
    NO[notification]
    GE[geo]
    SE[settings]
    ID[identity]
    FCM(["FCM push"])
    OSRM(["OSRM (optional)"])

    RI --- RIdb
    DI --- DIdb
    RI --- RD
    DI --- RD

    DI -->|"RiderApi: findAvailable, getRider"| RI
    DI -->|"GeoApi: route, SettingsApi: delivery"| GE
    RI -->|"GeoApi: route, SettingsApi: delivery, locale"| SE
    RI -->|"IdentityApi: grant or revoke rider access"| ID
    GE -->|"routes when set"| OSRM
    NO -->|"push"| FCM

    OR -. "foodstack.order.events<br/>OrderAccepted, OrderReady, OrderPickedUp,<br/>OrderDelivered, OrderCancelled, OrderRejected, OrderFailed" .-> DI
    DI -. "foodstack.dispatch.events<br/>RiderAssigned, RiderUnassigned, DeliveryPickedUp,<br/>DeliveryCompleted, DeliveryHandedBack" .-> OR
    DI -. "RiderOffered, RiderAssigned, RiderUnassigned,<br/>DeliveryPickedUp, DeliveryCompleted,<br/>DeliveryCancelled, DeliveryHandedBack" .-> RI
    DI -. "RiderOffered, RiderAssigned, DispatchAlert" .-> NO
```

`dispatch` calls `GeoApi` and `SettingsApi`, and `rider` calls both too; each pair is drawn as one arrow. `rider` publishes `RiderStatusChanged` on `foodstack.rider.events`, which nothing reads yet.

### Events with no consumer yet

These are published to Kafka today but nothing reads them yet.

| Topic | Events | Expected reader |
|---|---|---|
| `foodstack.identity.events` | `UserTokensRevoked` | — (each server reads the revocation floor in Redis; the gateway checks only signature and expiry) |
| `foodstack.settings.events` | `SettingsChanged` | Caches in other replicas |
| `foodstack.media.events` | `MediaReady` | — |
| `foodstack.promotion.events` | `CouponRedeemed`, `CouponReleased` | Reports and ledger (M4) |
| `foodstack.order.events` | `OrderPlaced`, `OrderPreparing`, `OrderCollected` (read only by order's own projections) | Ledger (M4) |
| `foodstack.rider.events` | `RiderStatusChanged` | Reports (M4) |

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

- Permission checks run at both entry points. `-web` uses `@PreAuthorize` and `CurrentUser.requireStoreAccess`. `-impl` uses `ModuleAccess` (`requireAuthenticated`, `requireServiceOr`, `requireServiceSelfOr`), so a module answers another module only what the user behind the call could ask for. Background work (event listeners, sweeps, rebuilds, demo seeding) runs as the platform through `ServiceCalls` with a service token (`module:service`).
- Where a module is `http` or `off`, its `-core`, `-impl` and `-web` beans are not created; another module that needs it gets a Module-Switch HTTP client for its `-api` instead.
