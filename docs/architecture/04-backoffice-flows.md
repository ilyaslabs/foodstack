# 4. Back-office flows

[Back to index](README.md)

The back office is `frontend/projects/backoffice` (Angular, served by nginx under `/admin`). Operator staff and store owners use the same app; what they see depends on the permissions in their token.

## 4.1 Sign-in and silent token renewal

Authorization Code with PKCE against the `identity` module (Spring Authorization Server). The same flow is used by the customer app with client `foodstack-web`.

```mermaid
sequenceDiagram
    autonumber
    actor U as Staff user
    participant Ap as backoffice app<br/>(auth library)
    participant As as identity: authorization server<br/>/oauth2/*
    participant Lg as identity: login page<br/>/login (server session)
    participant Mo as MongoDB<br/>foodstack_identity

    U->>Ap: open /admin
    Ap->>Ap: no token in sessionStorage → create PKCE verifier + challenge
    Ap->>As: redirect GET /oauth2/authorize?client_id=foodstack-backoffice<br/>&code_challenge=…&scope=openid
    As-->>U: 302 to /login (no login session yet)
    U->>Lg: email + password
    Lg->>Mo: identity_users (Argon2id hash check)
    Lg-->>U: login session cookie (held in server memory), back to /oauth2/authorize
    As->>Mo: save authorization (code) in identity_authorizations
    As-->>Ap: 302 /admin/auth/callback?code=…
    Ap->>As: POST /oauth2/token (code + verifier)
    As->>Mo: load authorization, users (roles, perms, brandIds, storeIds, tokenVersion)
    As->>As: sign JWT with ES256, 10-minute lifetime,<br/>claims: sub, roles, perms, tv, brandIds?, storeIds?
    As-->>Ap: access token (no refresh token for public clients)
    Ap->>Ap: keep token in sessionStorage (this tab only)

    Note over Ap,As: About 1 minute before expiry
    Ap->>As: same-origin fetch of /oauth2/authorize (new PKCE pair)
    As-->>Ap: redirect to callback with a new code (login session still alive)
    Ap->>As: POST /oauth2/token → new access token
    Note over Ap: A 401 from the API triggers one renewal and a retry.<br/>If the login session has ended (server restart), the user is sent to /login.
```

The signing keys come from `FOODSTACK_SIGNING_KEYS`. Without them (development only) a temporary key is generated, and every token stops working on restart.

## 4.2 Store change → search index (the read model)

Every back-office write in `store` or `geo` ends with an event, and search rebuilds that branch's document from the source modules. This is the path that keeps [flow 3.1](03-customer-flows.md#31-nearby-store-search) correct.

```mermaid
sequenceDiagram
    autonumber
    participant Kf as Kafka<br/>store / geo events
    participant Ls as CatalogueEventsListener<br/>(search-core)
    participant Ix as StoreIndexer
    participant St as StoreApi → StoreListingService<br/>(store module)
    participant Rd as Redis
    participant Mo as MongoDB<br/>foodstack_store
    participant Ge as GeoApi → QuoteService<br/>(geo module)
    participant Es as Elasticsearch

    Kf->>Ls: StoreChanged / StoreAvailabilityChanged /<br/>ItemAvailabilityChanged / ZoneChanged {storeId}
    Ls->>Ix: refresh(storeId)
    Note over Kf,Ls: BrandChanged / MenuChanged {brandId}: refresh every indexed branch of the brand<br/>OperatorDeliveryPricingChanged: refresh every indexed OPERATOR-delivery branch
    Ix->>St: getListing(storeId)
    St->>Mo: store_stores, store_brands
    St->>Rd: settings locale (cache), effective menu (cache)
    St-->>Ix: listing: names, cuisines, hours, holidays, status,<br/>dish names per language, dietary tags, location
    alt branch missing, not LIVE, or has no location
        Ix->>Es: delete document
    else live
        Ix->>Ge: deliveryArea(storeId, location, deliveryMode)
        Ge->>Mo: geo_zones (SELF) or geo_settings (OPERATOR)
        Ge-->>Ix: polygons (zones, or a circle of max distance ÷ 1.3)
        Ix->>Es: index document id=storeId (require_alias)
    end
```

- The listener reads only the id from the event. A duplicate or late event just rewrites the current state, so no inbox is needed.
- In the split topology, `getListing` and `deliveryArea` are HTTP calls from the search Deployment to the store and geo Deployments. The Kafka listener has no user, so no `Authorization` header is sent; it works because `-impl` classes do no permission checks (see [Code vs design](05-code-vs-design.md)).
- On start-up, if the index did not exist, it is created and filled in the background (`SearchIndexBootstrap`). `POST /api/v1/search/reindex` (`search:manage`) re-reads every branch on demand.

## 4.3 Onboard a store (operator)

`POST /api/v1/stores` (`store:manage`).

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator staff
    participant Sc as StoresController
    participant Ss as StoreService
    participant Se as SettingsApi
    participant Mo as MongoDB foodstack_store

    Op->>Sc: POST /api/v1/stores {brandName, branchName, address, location, …}
    Sc->>Ss: onboard(command, userId)
    Ss->>Se: getLocale() (languages, currency, time zone)
    Ss->>Ss: build brand + branch (status DRAFT),<br/>pick a free slug from the names (-2, -3 … if taken)
    Note over Ss,Mo: One transaction
    Ss->>Mo: insert store_slugs {_id: slug} (a race on the same slug → 409 SLUG_TAKEN)
    Ss->>Mo: insert store_brands, store_stores
    Ss->>Mo: insert store_outbox: BrandChanged, StoreChanged
    Ss-->>Op: 201 StoreDetails
    Note over Mo: Relay → Kafka → search: branch is DRAFT, so it is removed from / kept out of the index
```

Adding an owner (`POST /api/v1/stores/{id}/owners`) calls `IdentityApi.grantStoreAccess`, which creates or updates the user with role `STORE_OWNER` and the brand and branch ids, so they appear in the user's next token. Removing an owner calls `revokeStoreAccess`, which also raises the user's token version so the old token stops working at once.

## 4.4 Edit the menu, mark sold out

```mermaid
sequenceDiagram
    autonumber
    actor Ow as Store owner
    participant Bc as BrandsController / StoresController
    participant Ms as MenuService
    participant Md as MediaApi
    participant Mo as MongoDB foodstack_store
    participant Rd as Redis

    alt create or update an item (brand level)
        Ow->>Bc: POST/PUT /api/v1/brands/{brandId}/menu/items[/{itemId}]
        Bc->>Bc: requireBrandAccess(brandId) (brandIds claim or store:manage)
        Bc->>Ms: createItem / updateItem (version in body)
        Ms->>Md: claim new image (and release the old one)
        Note over Ms,Mo: transaction
        Ms->>Mo: save store_menu_items
        Ms->>Mo: outbox MenuChanged {brandId}
        Ms->>Rd: DEL foodstack:store:menu:{every branch of the brand}
    else sold out (branch level)
        Ow->>Bc: PUT /api/v1/stores/{storeId}/menu/items/{itemId}/sold-out
        Bc->>Bc: requireStoreAccess(storeId) (storeIds claim or store:manage)
        Bc->>Ms: setSoldOut
        Note over Ms,Mo: transaction
        Ms->>Mo: upsert store_item_overrides {_id: storeId:itemId[:optionId]}
        Ms->>Mo: outbox ItemAvailabilityChanged {storeId}
        Ms->>Rd: DEL foodstack:store:menu:{storeId}
    end
    Note over Mo: Relay → Kafka → search re-reads the branch (flow 4.2):<br/>sold-out dishes drop out of dish search
```

## 4.5 Draw delivery zones

`PUT /api/v1/zones/stores/{storeId}` (store owner of that branch, or `store:manage`). The back office draws polygons on a MapLibre map; the API also accepts a centre and radius, turned into a 16-corner polygon.

```mermaid
sequenceDiagram
    autonumber
    actor Ow as Store owner
    participant Zc as ZonesController (geo-web)
    participant Zs as ZoneService (geo-core)
    participant Mo as MongoDB foodstack_geo

    Ow->>Zc: PUT zones [{polygon, chargeMinor, minOrderMinor, freeDeliveryAboveMinor}]
    Zc->>Zc: requireStoreAccess(storeId)
    Zc->>Zs: replaceZones(storeId, zones)
    Zs->>Zs: validate: name, 3 to 200 corners (or centre + radius 200 m to 50 km),<br/>no crossing edges, amounts not negative → else 400 ZONES_INVALID
    Note over Zs,Mo: transaction
    Zs->>Mo: delete geo_zones of the branch, insert the new ones
    Zs->>Mo: outbox ZoneChanged {storeId}
    Zs-->>Ow: zones
    Note over Mo: Relay → Kafka → search: new deliveryArea shape (flow 4.2)
```

`geo` does not check that the store exists; it trusts the `storeIds` claim or the operator permission.

## 4.6 Go live

`PUT /api/v1/stores/{id}/status {LIVE}` (`store:manage`). `StoreService.setStatus` first runs the readiness check: the branch needs a location, at least one menu item on its brand, and, unless pickup is on, a delivery area (asked from `GeoApi.deliveryArea`). If something is missing the answer is 409 `STORE_NOT_READY` with the list. Otherwise the branch is saved with a `StoreChanged` event, and search indexes it (flow 4.2), after which it appears in [flow 3.1](03-customer-flows.md#31-nearby-store-search).

## 4.7 Upload an image (logo, cover, dish photo)

```mermaid
sequenceDiagram
    autonumber
    participant Ap as backoffice app
    participant Mc as MediaController (media-web)
    participant Ms as MediaService (media-core)
    participant Mo as MongoDB foodstack_media
    participant S3 as Object storage (RustFS / S3)
    participant Ow as Owning module<br/>(store or settings)

    Ap->>Mc: POST /api/v1/media/uploads {purpose, contentType, size} (media:upload)
    Mc->>Ms: startUpload
    Ms->>Mo: insert media_assets (PENDING_UPLOAD)
    Ms->>S3: presign PUT in the private bucket
    Ms-->>Ap: mediaId + upload URL
    Ap->>S3: PUT file directly (through Caddy /foodstack-private/*)
    Ap->>Mc: POST /api/v1/media/uploads/{id}/complete
    Mc->>Ms: completeUpload
    Ms->>S3: read raw upload
    Ms->>Ms: check type, strip metadata, rotate,<br/>resize to thumb / card / detail, encode WebP
    Ms->>S3: write variants: public bucket with immutable cache headers<br/>(logos, covers, dishes), private bucket otherwise
    Note over Ms,Mo: transaction
    Ms->>Mo: media_assets READY with variant URLs
    Ms->>Mo: outbox MediaReady
    Ms->>S3: delete raw upload
    Ms-->>Ap: asset with URLs
    Ap->>Ow: save the brand / item / branding with the mediaId
    Ow->>Ms: MediaApi.claim(mediaId, purpose, owner)<br/>(fails if not READY, wrong purpose or owned by someone else)
```

## 4.8 Change branding or locale

`PUT /api/v1/settings/branding` or `/locale` (`settings:manage`, `If-Match` required). `SettingsService` claims a new logo through `MediaApi`, saves `settings_settings` with an outbox `SettingsChanged` in one transaction, releases the old logo, and deletes the Redis key `foodstack:settings:cache:branding` (or `:locale`). The next read in any module reloads it. Nothing consumes `SettingsChanged` yet; the customer app reads branding on each server render.

## 4.9 Sign out everywhere

`POST /api/v1/me/revoke-tokens`. `UserService.revokeTokens` raises the user's `tokenVersion`, saves it with an outbox `UserTokensRevoked`, then writes the new minimum to Redis (`foodstack:identity:revoked:{userId}`). From then on every module rejects older tokens (flow 2.1), within the 5-second local cache.
