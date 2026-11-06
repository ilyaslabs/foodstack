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
        P["Caddy (foodstack-proxy)<br/>TLS, routing by path, rate limits per IP,<br/>security headers, 25 MB body limit"]
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
    P -- "/api/*, /oauth2/*, /.well-known/*" --> S
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
| `foodstack.identity.events` | `UserTokensRevoked` | Gateway token cache (M5) |
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
