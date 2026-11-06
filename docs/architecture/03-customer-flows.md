# 3. Customer flows

[Back to index](README.md)

The customer app is `frontend/projects/web` (Angular, server-side rendered on Node). Every page is rendered on the server per request; code that needs the browser (saved location, map, delivery quote) runs only after the page reaches the browser (`afterNextRender`).

## 3.1 Nearby store search

`GET /api/v1/search/stores?lat=&lng=&q=&cuisine=&fulfilment=DELIVERY|PICKUP&radius=&page=&size=` (public, no token).

**What is used:** Elasticsearch only. No MongoDB, no Redis, no Kafka on this path.

```mermaid
sequenceDiagram
    autonumber
    actor Cu as Customer
    participant Wb as web app (browser)
    participant Cd as Caddy
    participant Ct as SearchController<br/>(search-web)
    participant Sv as StoreSearchService<br/>(search-core)
    participant En as ElasticsearchStoreSearchEngine
    participant Es as Elasticsearch<br/>alias foodstack_search_stores

    Cu->>Wb: open home page
    Wb->>Wb: restore saved location (localStorage),<br/>or ask for one (GPS or address search, flow 3.2)
    Cu->>Wb: type "biryani" (debounced 250 ms), pick a cuisine chip
    Wb->>Cd: GET /api/v1/search/stores?lat=31.51&lng=74.34&q=biryani
    Cd->>Ct: public endpoint, no token check
    Ct->>Sv: search(query)
    Sv->>Sv: validate lat/lng, text ≤ 100 chars, cuisine pattern,<br/>clamp size 1..50, radius = smallest discovery step ≥ the asked one<br/>(8, 15, 25 km road), stop at 500 results (empty page past it)
    Sv->>En: search(clean query, from)
    En->>Es: one _search request
    Note right of Es: filter DELIVERY: geo_shape deliveryArea intersects the point<br/>AND geo_distance ≤ radius / 1.3 (road → straight line)<br/>filter PICKUP: pickupEnabled and within 15 km<br/>must (if text): bool_prefix OR fuzzy multi_match on<br/>brandName^4, cuisines^3, branchName^2, area, dishes.*, description.*<br/>post_filter: cuisine (so chip counts stay complete)<br/>collapse on brandId (nearest branch per brand)<br/>sort: _score (if text), then distance<br/>aggs: cuisine counts, distinct brands
    Es-->>En: hits with distance, cuisine buckets, brand count
    En-->>Sv: Result(hits, cuisines, total)
    Sv->>Sv: work out "open now" for each hit from the hours,<br/>holidays and temporary-close flag copied into the index
    Sv->>Sv: move open branches before closed ones (within this page)
    Sv-->>Ct: StoreSearchPage(cards, cuisines, total, hasMore,<br/>radiusMeters, widerRadiusMeters)
    Ct-->>Wb: 200 JSON
    Wb-->>Cu: store cards with distance, open/closed, cuisine chips,<br/>"within 8 km" and "Show restaurants up to 15 km"
    Cu->>Wb: Show further away
    Wb->>Cd: same search with radius=15000

    alt Elasticsearch unreachable
        En-->>Ct: ModuleException SEARCH_UNAVAILABLE
        Ct-->>Wb: 503 problem+json
    end
```

**Where the "delivers to me" answer comes from.** Each indexed branch carries a `deliveryArea` shape. For a self-delivery branch it is the union of the zone polygons the owner drew. For an operator-delivery branch it is a 48-point circle around the branch whose radius is the operator's maximum delivery distance divided by the road factor (1.3). The shape is computed by `geo` when the branch is indexed (flow 4.2), so the search request itself never calls `geo`.

**Discovery radius.** A self-delivery branch may draw zones as large as it likes, so the zone alone could fill a search with far-away branches. Search therefore also asks for the branch to be within the discovery radius of the customer (road distance, `foodstack.search.discovery-steps-km`, default 8, 15 and 25 km, divided by `road-factor` 1.3 for the straight-line filter). The page says which radius it used and the next step, and the customer widens it with one tap. Pickup uses its own 15 km radius. A direct branch link and checkout never apply the radius: only the zone decides whether a branch delivers.

**Scale settings.** Shards and replicas of the index come from `foodstack.search.shards` and `auto-expand-replicas` (default `0-1`, `0-all` on a large cluster); a branch's document is written with its version as an external version, so a late, older re-read is refused; brand lookups page through a point in time.

**Languages.** Dish names and descriptions are stored per language (`dishes.en`, `dishes.ar`, …) with one analyzer that lower-cases, folds accents and normalises Arabic and Persian script, so "برياني" and "biryani" each match their own language's text.

**How fresh the index is.** A change in the back office reaches search after: commit, then the outbox relay's next poll (up to 100 ms), then Kafka delivery, then the re-read and index write, then Elasticsearch's refresh (about 1 s). In practice a second or two.

## 3.2 Address lookup (location picker)

`GET /api/v1/geo/addresses?q=&lang=` and `GET /api/v1/geo/addresses/reverse?lat=&lng=&lang=` (public).

```mermaid
sequenceDiagram
    autonumber
    actor Cu as Customer
    participant Wb as web app (browser)
    participant Gc as GeoController<br/>(geo-web)
    participant Gs as GeocodingService<br/>(geo-core)
    participant Rd as Redis
    participant Nm as Nominatim

    alt use my location
        Cu->>Wb: allow GPS
        Wb->>Gc: GET /api/v1/geo/addresses/reverse?lat&lng&lang
    else type an address
        Cu->>Wb: type "Gulberg"
        Wb->>Gc: GET /api/v1/geo/addresses?q=Gulberg&lang=en
    end
    Gc->>Gs: search / reverse
    alt provider is "none"
        Gs-->>Wb: 503 GEOCODING_DISABLED
    end
    Gs->>Rd: GET foodstack:geo:cache:geocode:…
    alt cache hit
        Rd-->>Gs: suggestions
    else miss
        Gs->>Nm: HTTP search or reverse (timeout, User-Agent, country filter)
        Nm-->>Gs: places
        Gs->>Rd: SET with 7-day expiry
    end
    Gs-->>Wb: suggestions [label, lat, lng]
    Wb->>Wb: customer picks one, saved in localStorage
```

## 3.3 Store page and menu

`GET /api/v1/stores/{slug-or-id}` and `GET /api/v1/stores/{id}/menu` (public). The page is rendered on the Node server for the first load (so search engines and link previews see the menu) and in the browser on later navigation.

```mermaid
sequenceDiagram
    autonumber
    actor Cu as Customer
    participant Wb as web app<br/>(Node SSR or browser)
    participant Sc as StoresController<br/>(store-web)
    participant Ss as StoreService
    participant Ms as MenuService
    participant Se as SettingsApi<br/>(settings module)
    participant Rd as Redis
    participant Mo as MongoDB<br/>foodstack_store

    Cu->>Wb: open /stores/spice-route-gulberg
    Wb->>Sc: GET /api/v1/stores/spice-route-gulberg
    Sc->>Ss: resolve(key)
    alt key looks like an id
        Ss-->>Sc: same id
    else slug
        Ss->>Mo: store_slugs.findById(slug)
        Mo-->>Ss: storeId, or 404 STORE_NOT_FOUND
    end
    Sc->>Ss: get(storeId)
    Ss->>Mo: store_stores + store_brands
    Ss->>Se: getLocale()
    Se->>Rd: GET foodstack:settings:cache:locale (10 min)
    Rd-->>Se: locale (or read settings_settings on a miss)
    Ss-->>Sc: StoreDetails
    Sc->>Sc: not LIVE? only operator staff or the branch's own owner may see it, else 404
    Sc-->>Wb: store page JSON

    Wb->>Sc: GET /api/v1/stores/{id}/menu
    Sc->>Ms: effectiveMenu(storeId)
    Ms->>Rd: GET foodstack:store:menu:{storeId}
    alt cache hit
        Rd-->>Ms: effective menu JSON
    else miss
        Ms->>Mo: store_menu_sections, store_menu_items (brand),<br/>store_item_overrides (branch sold-out marks)
        Ms->>Ms: drop items limited to other branches,<br/>apply branch price overrides and sold-out marks,<br/>group by section in sort order
        Ms->>Rd: SET with 10-min expiry
    end
    Ms-->>Sc: EffectiveMenu
    Sc-->>Wb: menu JSON
    Wb->>Wb: set title, description, canonical link /stores/{slug}
    Wb-->>Cu: page with menu in the chosen language (RTL for Arabic or Urdu)
```

## 3.4 Delivery quote on the store page

`GET /api/v1/stores/{id}/delivery-quote?lat=&lng=` (public). Runs only in the browser, once a saved location exists.

```mermaid
sequenceDiagram
    autonumber
    participant Wb as web app (browser)
    participant Sc as StoresController
    participant Ss as StoreService (store-core)
    participant Ga as GeoApi → QuoteService (geo-core)
    participant Rd as Redis<br/>geo quote caches
    participant Mo as MongoDB<br/>foodstack_store / foodstack_geo

    Wb->>Sc: GET /api/v1/stores/{id}/delivery-quote?lat&lng
    Sc->>Ss: quote(storeId, point)
    Ss->>Mo: store_stores (location, delivery mode, prep minutes, time zone)
    Ss->>Ga: quote(storeId, storeLocation, deliveryMode, destination, timezone)
    Ga->>Ga: distance = straight line × 1.3 (road factor)
    alt deliveryMode = OPERATOR
        Ga->>Rd: geo:cache:operator-pricing:{gen}
        opt cache miss
            Ga->>Mo: geo_settings (operator pricing), then cache it
        end
        Ga->>Ga: off → OPERATOR_DELIVERY_OFF<br/>beyond max distance → TOO_FAR<br/>else charge = base + per-km × km
        opt now is in a peak window (the branch's own time zone, sent with the quote)
            Ga->>Ga: charge × peak multiplier
        end
        Ga->>Ga: the operator's minimum order and free-delivery threshold
    else deliveryMode = SELF
        Ga->>Rd: geo:cache:zones:{storeId}:{gen}
        opt cache miss
            Ga->>Mo: geo_zones of the branch, in sort order, then cache them
        end
        Ga->>Ga: first zone whose polygon contains the point gives<br/>charge, minimum order, free-delivery threshold<br/>none → OUT_OF_AREA
    end
    Ga-->>Ss: DeliveryQuoteView
    Ss->>Ss: minutes = prep minutes + distance at 20 km/h
    Ss-->>Wb: deliverable, reason, charge, min order, free above, distance, minutes, currency
```

The point-in-polygon test runs in Java (`GeoMath.contains`), not in MongoDB. A branch's zones and the operator pricing are cached in Redis for 10 minutes (`GeoCache`, generation keys raised after each change and again by `geo`'s event listener on every replica), so a quote normally reads no MongoDB in `geo`. Peak windows are days and times in the branch's local time zone (each branch has its own, found from its location); a window whose end is before its start runs past midnight. Checkout enforces the quote's minimum order and free-delivery threshold for both delivery modes.
