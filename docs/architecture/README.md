# How FoodStack works (architecture and flows)

These pages show what the code on `main` **actually does today**, traced from the source, not what the design intends. Where the code and the [TDD](../TDD.md) differ, the difference is listed in [Code vs design](05-code-vs-design.md).

State described: after M4 (modules `identity`, `settings`, `media`, `geo`, `store`, `search`, `promotion`, `cart`, `payment`, `order`, `notification`, `rider`, `dispatch`, `ledger`, `review`).

All diagrams are [Mermaid](https://mermaid.js.org/) and render directly on GitHub.

## Pages

| Page | What it answers |
|---|---|
| [1. Big picture](01-big-picture.md) | What runs where, which module owns which data, who calls whom, who sends events to whom |
| [2. Platform mechanics](02-platform-mechanics.md) | What happens to every request (security, token check), how events travel (outbox, relay, Kafka), what is in Redis, how a module gets its database, how modules call each other when split |
| [3. Customer flows](03-customer-flows.md) | Nearby store search, address lookup, store page and menu, delivery quote |
| [4. Back-office flows](04-backoffice-flows.md) | Sign-in and token renewal, onboarding a store, editing the menu, sold-out, drawing zones, going live, image upload, branding, sign-out everywhere, index rebuild |
| [5. Code vs design](05-code-vs-design.md) | Every place where today's code differs from the TDD, and what is not built yet |
| [6. Ordering flows](06-ordering-flows.md) | The order's life, cart and checkout, placing a cash order, card payment on the hosted page, the store's live board, no answer, cancel and refunds, coupons, notifications and emails, sign-in by email code and password reset |
| [7. Delivery flows](07-delivery-flows.md) | The delivery job's life, how dispatch finds and offers a rider, the rider accepting, live position and tracking, pickup and handover with the code, support reassigning or handing back, stores that deliver themselves, push devices, saved addresses |
| [8. How FoodStack scales out](08-scaling.md) | The scaled-out picture, how requests, events, background work, live updates, data, cache and search are split between machines, what happens when a replica joins or dies, the growth stages and what each one changes, and the limits more machines do not lift |
| [9. Money, reviews and privacy](09-money-and-trust-flows.md) | Ledger postings for a finished order, roll-ups, payouts and settlement, stores that collect card payments themselves and their credit limit, card fields inside the app and saved cards, reviews and moderation, data export and account deletion, the operator's message editor |

## Quick answer: which store does each action touch?

✅ = used on this path, `—` = not used. "Kafka" means the action writes an event to its module's outbox, which the relay then sends to Kafka.

| Action | MongoDB | Redis | Elasticsearch | Kafka | Object storage | External |
|---|---|---|---|---|---|---|
| Nearby store search (`GET /api/v1/search/stores`) | — | — | ✅ only store (discovery radius and zone) | — | — | — |
| Address search / reverse geocode | — | ✅ cache (read, then write on miss) | — | — | — | Nominatim on a cache miss |
| Store page (`GET /api/v1/stores/{slug}`) | ✅ | ✅ settings cache | — | — | — | — |
| Store menu (`GET /api/v1/stores/{slug}/menu`) | ✅ on a cache miss | ✅ menu cache | — | — | — | — |
| Delivery quote | ✅ store; zones or operator pricing on a cache miss | ✅ settings cache, zones and pricing caches | — | — | — | — |
| Sign-in and token refresh (`/api/v1/auth/*`) | ✅ users, refresh tokens | ✅ sign-in rate limit | — | — | — | — |
| Any authenticated API call | — | ✅ revoked token version (5 s bounded local cache) | — | — | — | — |
| Onboard store, edit branch, hours, menu, sold-out, zones, go live | ✅ | ✅ menu cache evicted on menu edits | ✅ later, through the search listener | ✅ | — | — |
| Image upload | ✅ | — | — | ✅ `MediaReady` | ✅ | — |
| Branding / locale change | ✅ | ✅ settings cache evicted | — | ✅ `SettingsChanged` | ✅ logo claim | — |
| Cart: add, change, read (`/api/v1/carts/me`) | ✅ cart, store menu on a cache miss | ✅ menu cache | — | — | — | — |
| Checkout quote (`POST /api/v1/carts/me/checkout`) | ✅ cart, store, zones or pricing, promotions | ✅ menu and settings caches, coupon-check limit | — | — | — | — |
| Place an order (`POST /api/v1/orders`) | ✅ order, placement key, coupon counters, cart | ✅ placement limit, live message | — | ✅ `OrderPlaced`, `OrderConfirmed` | — | — |
| Card payment | ✅ payment | — | — | ✅ `PaymentSucceeded` / `PaymentFailed` | — | Card gateway (page, webhook, refunds) |
| Store accepts, ready, collected | ✅ order, order_active | ✅ live message | — | ✅ one event per step | — | — |
| Live order page and board (SSE) | ✅ on each reload | ✅ pub/sub subscription | — | — | — | — |
| Saved addresses (`/api/v1/me/addresses`) | ✅ identity_addresses | — | — | — | — | — |
| Dispatch matcher (every 2 s, each replica over its work buckets) | ✅ dispatch_active, dispatch_jobs | ✅ bucket leases, offer lock, rider GEO sets per cell and positions, live message | — | ✅ `RiderOffered`, `DispatchAlert` | — | Routing (OSRM) when set |
| Rider accepts, arrives, picks up, delivers | ✅ dispatch_jobs, dispatch_active | ✅ offer lock released, code tries limit, live message | — | ✅ `RiderAssigned`, `DeliveryPickedUp`, `DeliveryCompleted` | ✅ proof photo (through media) | — |
| Rider position (`POST /api/v1/rider/me/locations`) | ✅ sampled: rider_riders, rider_locations | ✅ position, GEO set of its cell, rate limit, live message on its cell | — | — | — | — |
| Customer tracking (`/api/v1/riders/tracking/{orderId}`) | ✅ rider_assignments | ✅ position, pub/sub for the stream | — | — | — | Routing (OSRM) when set |
| Push to a device | ✅ notification_devices | — | — | read: dispatch, order events | — | FCM |
| Notification and email | ✅ inbox, deliveries, preferences | — | — | read: order, payment, identity events | — | SMTP server |
| Sign in with an email code | ✅ users, outbox | ✅ code, rate limit | — | ✅ `EmailCodeIssued` | — | SMTP (through notification) |
| Order done or refunded, card paid, rider delivered (ledger) | ✅ ledger_transactions, ledger_orders, ledger_credit | — | — | read: order, payment, dispatch events; writes `StoreCreditLimitExceeded` / `StoreCreditRestored` | — | — |
| Payout report, statement, settle (`/api/v1/ledger/*`) | ✅ ledger_daily plus lines not rolled up yet, ledger_settlements | — | — | ✅ `PayoutSettled`, `StoreBalanceSettled` | — | — |
| Ledger roll-ups (each replica over its work buckets) | ✅ ledger_transactions, ledger_daily | ✅ bucket leases | — | — | — | — |
| Connect a store's merchant account | ✅ payment_merchant_accounts | — | — | — | — | Card gateway (test call) |
| Card page in the app, saved cards (`/api/v1/payments/*`) | ✅ payment, gateway customer reference | — | — | ✅ `PaymentSucceeded` | — | Card gateway (session, cards) |
| Rate an order, reply, moderate (`/api/v1/reviews`) | ✅ review_reviews, review stats | — | — | ✅ `ReviewPublished`, `ReviewHidden` | ✅ photo (through media) | — |
| Delete my account (`POST /api/v1/me/deletion`, then the sweep) | ✅ users, then every module's own data | ✅ revoked token version | — | ✅ `AccountDeletionScheduled`, `UserDeleted` | ✅ review photos released | Card gateway forgets the customer |
| Edit a message (`/api/v1/notifications/templates`) | ✅ notification_templates (read again on every send) | — | — | — | — | — |

**Short answer for search:** the customer search request reads **only Elasticsearch**. It does not touch MongoDB or Redis. MongoDB, Redis and Kafka are used *earlier*, when a store change is written and the search module re-reads the branch to update the index (see [flow 3.1](03-customer-flows.md#31-nearby-store-search) and [2.3](02-platform-mechanics.md#23-events-outbox-relay-kafka-consumer)).

## Keeping these pages true

Each milestone that changes a flow updates the matching diagram in the same pull request. When the code and a diagram disagree, the code wins and the diagram is fixed.
