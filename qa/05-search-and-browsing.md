# 05 Search and browsing

[Back to QA index](README.md)

What a customer sees before ordering: picking a location, finding stores, the store page and menu, the delivery fee, and saved addresses.

Search reads only Elasticsearch, through the alias `foodstack_search_stores` (it points at an index such as `foodstack_search_stores_v3`). The store page reads `foodstack_store`. Addresses are in `foodstack_identity.identity_addresses`.

Demo stores in Lahore: **Spice Route Gulberg** (31.512, 74.344; delivered by the platform's riders), **Napoli Slice DHA Phase 5** (delivers itself, with zones). A good test point for Gulberg is 31.518, 74.352.

### SRCH-01 First visit asks for a location

Who: nobody signed in, private window · Where: customer app

Steps
1. Open `http://localhost:4200/`.
2. Type `Gulberg` in **Search your area or street** and pick a suggestion.

Expect
- Before step 2: "Pick a location to see stores". No store list yet.
- After step 2: the header says **Delivering to** and the place. Stores that deliver there are listed, open ones first, each with its distance, delivery time and **Open** or **Closed**.
- Reload: the location is remembered.

Check
- Browser, Local Storage: the saved location (label, lat, lng).
- Network: `GET /api/v1/geo/addresses?q=Gulberg&lang=en`, then `GET /api/v1/search/stores?lat=…&lng=…&fulfilment=DELIVERY`.
- Redis: `foodstack:geo:cache:geocode:…` holds the suggestions for 7 days (`TTL` about 604800).

Address search asks OpenStreetMap's Nominatim, so it needs internet access. Without it, step 2 says "Address search is not available right now. Use your location instead." (the API answers 503 `GEOCODING_UNAVAILABLE`): use **Use my location** (SRCH-02) instead.

### SRCH-02 Use my location

Who: nobody · Where: customer app

Steps
1. Click **Use my location** and allow it. (In Chrome's F12 > Sensors you can set 31.518, 74.352.)
2. Repeat, but block the browser's location permission.

Expect
1. "Finding you…", then the stores near you, with the header showing **Your current location** or the street name.
2. "Location is off. Type your area instead."

### SRCH-03 Search by name, dish and cuisine

Who: nobody · Where: customer app, location 31.518, 74.352

Steps
1. Type `spice` in **Search stores or dishes**.
2. Type `tikka`.
3. Type `tika` (a typo).
4. Clear the text and click the **BBQ** cuisine chip.
5. Search for a dish you marked sold out (STORE-06).

Expect
1. Spice Route first.
2. Restaurants that serve tikka.
3. Still finds tikka.
4. Only BBQ restaurants. The chip counts do not change when a chip is picked.
5. A sold-out dish does not make its branch match.
- One brand shows once, as its nearest branch, with "{n} branches" when it has more.

Check
- Network: `GET /api/v1/search/stores?…&q=tikka`. The answer lists `stores`, `cuisines` with counts, `total`, `radiusMeters` and `widerRadiusMeters`.

### SRCH-04 Wider search

Who: nobody · Where: customer app, a location with few restaurants within 8 km

Steps
1. Scroll to the end of the list.
2. Click **Show restaurants up to 15 km**, then **Show restaurants up to 25 km**.

Expect
- The list starts "within 8 km". Each click widens the search and adds stores further away.
- At 25 km there is no further button.

### SRCH-05 Delivery or pickup

Who: nobody · Where: customer app

Steps
1. Switch the top toggle from **Delivery** to **Pickup**.

Expect
- Stores with pickup on within 15 km are listed, whether or not they deliver to you. The header says **Pickup near**.

### SRCH-06 Store page and the delivery fee

Who: nobody · Where: customer app, `/stores/spice-route-gulberg`

Steps
1. Open the page with no location saved.
2. Set a location about 1 km away (31.518, 74.352).
3. Set a location 30 km away.
4. Open `/stores/napoli-slice-dha-phase-5` with a location inside, then outside, its zones.

Expect
1. Name, description, cuisines, opening hours, the menu grouped by section, and "Set your location to see the delivery fee".
2. "Delivery Rs …", the minimum order and "Free above …" if set, and about how many minutes.
3. "Does not deliver to your location. Pickup may be available."
4. Inside: the fee of the zone you are in. Outside: the same "does not deliver" note.
- The page title and the link preview carry the store name (view the page source: the menu is in the HTML, rendered on the server).

Check
- Network: `GET /api/v1/stores/spice-route-gulberg`, `GET /api/v1/stores/<id>/menu`, `GET /api/v1/stores/<id>/delivery-quote?lat=…&lng=…`. The quote answers `deliverable`, `reason` (`TOO_FAR`, `OUT_OF_AREA` or `OPERATOR_DELIVERY_OFF` when not), `chargeMinor`, `minOrderMinor`, `freeDeliveryAboveMinor`, `distanceMeters` and `etaMinutes`.
- Redis: `foodstack:store:menu:<storeId>` holds the menu for 10 minutes.
- The store page and menu answers (GET, not HEAD) have `Cache-Control: no-cache, public, s-maxage=30` and an `ETag`. A second request with `If-None-Match: <etag>` answers 304. A draft branch answers `no-store`.

### SRCH-07 A store that is not live

Who: nobody · Where: customer app, the address of a draft or paused branch (STORE-01, STORE-14)

Expect
- "This store is not available. It may have closed or moved." The same address opens for the branch's managers and for staff in the back office.

### SRCH-08 Ranking: open first, then nearness, rating and boost

Who: nobody · Where: customer app

Steps
1. Search near two open restaurants at about the same distance. Note the order.
2. Give one of them a better rating (REV-01 with several orders), or set its brand to **Top** (STORE-17).
3. Search again.
4. Close the first one for now (STORE-10) and search again.

Expect
- Step 3: the better rated or boosted one moves up.
- Step 4: the closed one moves below every open one.

Check
- Elasticsearch, the branch document: `ratingCount`, `ratingAverage`, `ratingScore` (a weighted average that pulls new stores toward 4.0), `searchBoost` (0–3) and `rankFactor`.

### SRCH-09 Back-office changes reach search in seconds

Who: admin · Where: back office, then customer app

Steps
1. Change Spice Route's description or add a dish named `QA Special`.
2. Within 5 seconds, search for `QA Special`.

Expect
- The change is found within a couple of seconds.

Check
- Elasticsearch `foodstack_search_stores/_doc/0195f0a0-0000-7000-8000-000000000101`: `dishes.en` contains `QA Special`, and `version` went up.
- Kafka `foodstack.store.events`: `MenuChanged`.

### SRCH-10 Saved addresses

Who: `customer@demo.foodstack.local` · Where: customer app, header location menu, **Saved addresses**

Steps
1. **Add address**: label **Home**, move the map so the pin sits on the door, enter "House 14, Street 7", directions "Blue gate", and save.
2. Add a **Work** address, then try to add a second **Home**.
3. Add an **Other** address named "Mum's place" and **Make default**.
4. Edit Work, then **Delete address**.

Expect
- Each saves. The default one is marked **Default** and is used when the app opens.
- Step 2: "You already have an address with this label. Edit that one, or choose Other."
- After 20 addresses: "You can keep up to 20 addresses. Delete one first."

Check
- `identity_addresses`: one document per address, with `userId`, `label` (`HOME`, `WORK`, `OTHER`), `name`, `lat`, `lng`, `addressLine`, `instructions`, `preferred` (only one `true`) and `version`.
- Deleting removes the document. Past orders keep their own copy of the address (`order_orders.destination`).

### SRCH-11 Language and right-to-left

Who: nobody · Where: customer app (with Arabic and Urdu on, SET-02)

Steps
1. Switch the language to Arabic, then Urdu, on the home page and a store page.

Expect
- Every word changes, the layout runs right to left, numbers and prices are formatted for the language, and dish names show in that language where the store gave one.

### SRCH-12 Search is down

Who: tester · Where: terminal, then customer app

Steps
1. `docker stop <elasticsearch container>`, then search.
2. `docker start <elasticsearch container>`.

Expect
1. "Search is not available right now. Try again in a moment." with **Try again**. Store pages and checkout still work.
2. Search works again within a minute.

Check
- Network: the search answers 503 with code `SEARCH_UNAVAILABLE`.
