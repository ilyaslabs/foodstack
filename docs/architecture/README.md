# How FoodStack works (architecture and flows)

These pages show what the code on `main` **actually does today**, traced from the source, not what the design intends. Where the code and the [TDD](../TDD.md) differ, the difference is listed in [Code vs design](05-code-vs-design.md).

State described: after M1 (modules `identity`, `settings`, `media`, `geo`, `store`, `search`). Ordering, payment, dispatch and the other modules are not built yet, so they do not appear here.

All diagrams are [Mermaid](https://mermaid.js.org/) and render directly on GitHub.

## Pages

| Page | What it answers |
|---|---|
| [1. Big picture](01-big-picture.md) | What runs where, which module owns which data, who calls whom, who sends events to whom |
| [2. Platform mechanics](02-platform-mechanics.md) | What happens to every request (security, token check), how events travel (outbox, relay, Kafka), what is in Redis, how a module gets its database, how modules call each other when split |
| [3. Customer flows](03-customer-flows.md) | Nearby store search, address lookup, store page and menu, delivery quote |
| [4. Back-office flows](04-backoffice-flows.md) | Sign-in and token renewal, onboarding a store, editing the menu, sold-out, drawing zones, going live, image upload, branding, sign-out everywhere, index rebuild |
| [5. Code vs design](05-code-vs-design.md) | Every place where today's code differs from the TDD, and what is not built yet |

## Quick answer: which store does each action touch?

✅ = used on this path, `—` = not used. "Kafka" means the action writes an event to its module's outbox, which the relay then sends to Kafka.

| Action | MongoDB | Redis | Elasticsearch | Kafka | Object storage | External |
|---|---|---|---|---|---|---|
| Nearby store search (`GET /api/v1/search/stores`) | — | — | ✅ only store | — | — | — |
| Address search / reverse geocode | — | ✅ cache (read, then write on miss) | — | — | — | Nominatim on a cache miss |
| Store page (`GET /api/v1/stores/{slug}`) | ✅ | ✅ settings cache | — | — | — | — |
| Store menu (`GET /api/v1/stores/{slug}/menu`) | ✅ on a cache miss | ✅ menu cache | — | — | — | — |
| Delivery quote | ✅ store, zones or operator pricing | ✅ settings cache | — | — | — | — |
| Sign-in (authorization code + PKCE) | ✅ users, authorizations | — | — | — | — | — |
| Any authenticated API call | — | ✅ revoked token version (5 s local cache) | — | — | — | — |
| Onboard store, edit branch, hours, menu, sold-out, zones, go live | ✅ | ✅ menu cache evicted on menu edits | ✅ later, through the search listener | ✅ | — | — |
| Image upload | ✅ | — | — | ✅ `MediaReady` | ✅ | — |
| Branding / locale change | ✅ | ✅ settings cache evicted | — | ✅ `SettingsChanged` | ✅ logo claim | — |

**Short answer for search:** the customer search request reads **only Elasticsearch**. It does not touch MongoDB or Redis. MongoDB, Redis and Kafka are used *earlier*, when a store change is written and the search module re-reads the branch to update the index (see [flow 3.1](03-customer-flows.md#31-nearby-store-search) and [2.3](02-platform-mechanics.md#23-events-outbox-relay-kafka-consumer)).

## Keeping these pages true

Each milestone that changes a flow updates the matching diagram in the same pull request. When the code and a diagram disagree, the code wins and the diagram is fixed.
