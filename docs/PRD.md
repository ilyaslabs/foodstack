# FoodStack: Product Requirements Document (PRD)

| | |
|---|---|
| Status | Draft v1, agreed in grooming with ilyas on 2026-10-27 |
| Owner | ilyas |
| Related | [BRD](BRD.md), [TDD](TDD.md) |

This document describes what FoodStack v1 does for each user and how it should feel. Business context is in the [BRD](BRD.md); the technical design is in the [TDD](TDD.md).

## 1. Personas

| Persona | Context | Primary goal |
|---|---|---|
| **Sara, customer** | Orders on her phone browser at lunch and dinner | Find good food nearby fast, know exactly when it arrives |
| **Omar, store owner** | Runs a busy restaurant, tablet at the counter | Never miss an order, keep the menu accurate, see what he earns |
| **Bilal, rider** | Motorbike, Android phone, patchy data | Get many jobs, short waits, clear directions, transparent pay |
| **Ayesha, operator admin** | Runs operations and support | Onboard stores and riders, fix problems, run promotions, settle money |
| **Daniyal, operator DevOps** | Small team, one Kubernetes cluster | Install, secure, observe and upgrade without surprises |

## 2. Applications

| App | Users | Form factor |
|---|---|---|
| **Customer web** (`web`) | Customers, guests | Mobile-first responsive web, installable PWA, SSR for SEO on store and menu pages |
| **Back office** (`backoffice`) | Admin, support, finance, store owner and staff, rider | Desktop-first for admin and store; the **rider area** is mobile-first and installable as a PWA |
| **REST API** | Web apps, future native apps, integrations | OpenAPI 3.1, versioned under `/api/v1` |

Role-based areas in the back office:

| Area | Roles |
|---|---|
| `/admin` | `ADMIN`, `SUPPORT`, `FINANCE`, `MARKETING` (fine-grained permissions) |
| `/store` | `STORE_OWNER`, `STORE_STAFF` (scoped to their store or stores) |
| `/rider` | `RIDER` |

## 3. Core order lifecycle

```
                 ┌────────────── CANCELLED (by customer before ACCEPTED, by store, by support) ─┐
                 │                                                                             │
PLACED ─► PAYMENT_PENDING ─► CONFIRMED ─► ACCEPTED ─► PREPARING ─► READY ─► PICKED_UP ─► DELIVERED
  (card only)           │         │                                    │
                        └ FAILED  └ REJECTED (store)                    └─► COLLECTED (pickup orders)
```

- Cash orders skip `PAYMENT_PENDING`.
- A store must accept within a configurable time (default 3 minutes) or the order auto-rejects and the customer is refunded.
- Dispatch starts at `ACCEPTED` for operator-fleet stores so the rider arrives around `READY`; self-delivery stores mark `PICKED_UP` and `DELIVERED` themselves.
- Every transition is timestamped, attributed to an actor, and visible on the order timeline.

## 4. Functional requirements

Priority: **M** = must for v1, **S** = should for v1, **C** = could (later).

### 4.1 Identity and accounts (`identity`)

| ID | Requirement | P |
|---|---|---|
| ID-1 | Customer sign-up and login by **phone OTP**; email and password as an alternative | M |
| ID-2 | Social login (Google, Apple) through OIDC, enabled per deployment | S |
| ID-3 | Guest browsing; login required at checkout | M |
| ID-4 | Admins invite back-office users by email; roles and permissions assigned by admin | M |
| ID-5 | Optional TOTP two-factor login for back-office users; mandatory for `ADMIN` and `FINANCE` | M |
| ID-6 | Customer profile with saved addresses (pin on map, label, notes) | M |
| ID-7 | Data export and account deletion requests, fulfilled within a configured time | M |
| ID-8 | Login rate limiting and lockout; audit log of back-office logins | M |

### 4.2 Stores and menus (`store`)

| ID | Requirement | P |
|---|---|---|
| ST-1 | Admin onboards a store: legal name, display name, logo, cover image, address and location, cuisines, tax ID, commission rate, delivery mode (operator fleet or self-delivery), pickup enabled | M |
| ST-2 | Opening hours per weekday, holiday closures, and a "pause orders" switch with reason and duration | M |
| ST-3 | Menu: categories, items (name, description, photos, price, tax class, dietary tags, allergens), modifier groups (required or optional, min and max choices, price deltas) | M |
| ST-4 | Item and modifier availability toggles (sold out today) | M |
| ST-5 | All menu text translatable per configured language | M |
| ST-6 | Store owner edits menu; changes to price can be set to require admin approval | S |
| ST-7 | Average preparation time per store, used for ETA | M |
| ST-8 | Bulk menu import from CSV | S |
| ST-9 | Store groups (one owner, many branches) | S |

### 4.3 Discovery and search (`search`, `geo`)

| ID | Requirement | P |
|---|---|---|
| SE-1 | Home shows stores that deliver to the chosen address, ordered by relevance (open now, distance, rating, ETA) | M |
| SE-2 | Full-text search across stores, cuisines and dishes, typo-tolerant, in every configured language | M |
| SE-3 | Filters: cuisine, rating, delivery fee, ETA, dietary tags, pickup available, offers | M |
| SE-4 | Autocomplete suggestions under 150 ms p95 | M |
| SE-5 | Admin-curated home sections (banners, featured stores, collections) | S |
| SE-6 | Address search and reverse geocoding on the map pin | M |
| SE-7 | Delivery zones drawn as polygons on a map by admins, each with fee rules and min order | M |

### 4.4 Cart and checkout (`cart`, `order`, `promotion`, `payment`)

| ID | Requirement | P |
|---|---|---|
| CO-1 | One cart per customer, one store per cart; switching store asks to clear | M |
| CO-2 | Checkout shows itemised subtotal, delivery fee, service fee, discounts, tax, tip (optional), total | M |
| CO-3 | Choose delivery or pickup; delivery address must be inside a zone served by the store | M |
| CO-4 | Apply one coupon per order; validation explains why a coupon fails | M |
| CO-5 | Pay by cash on delivery or card through the configured gateway; saved cards via gateway tokens | M |
| CO-6 | Price and availability re-validated at order placement; customer is told about any change | M |
| CO-7 | Idempotent order placement (double tap never creates two orders) | M |
| CO-8 | ETA shown before ordering and updated live after | M |
| CO-9 | Re-order from history in one tap | S |

### 4.5 Order fulfilment (`order`) and store experience

| ID | Requirement | P |
|---|---|---|
| OF-1 | Store order board (new, preparing, ready, out) updates live with a loud, repeating alert for new orders | M |
| OF-2 | Store accepts with a prep-time estimate or rejects with a reason | M |
| OF-3 | Store marks preparing and ready; prints a kitchen ticket (browser print) | M |
| OF-4 | Self-delivery stores mark picked up and delivered | M |
| OF-5 | Customer can cancel before acceptance; after that, only support can cancel | M |
| OF-6 | Support sees the full timeline and can cancel, refund fully or partly, or reassign a rider, always with a reason | M |
| OF-7 | Store dashboard: today's orders, revenue, average prep time, rating | S |

### 4.6 Dispatch and riders (`dispatch`, `rider`)

| ID | Requirement | P |
|---|---|---|
| DR-1 | Rider onboarding by admin: identity documents, vehicle type, zone, payout details | M |
| DR-2 | Rider goes online or offline; location reported every 10 s while online (adaptive when stationary) | M |
| DR-3 | Dispatch offers a job to the best-ranked available rider (distance to store, current load, acceptance rate); offer expires after 30 s and moves to the next rider | M |
| DR-4 | Job card shows store, drop-off area, distance, and earning before accepting | M |
| DR-5 | Navigation hand-off to the device's maps app | M |
| DR-6 | Proof of delivery: tap to confirm, optional photo, OTP for high-value orders (configurable) | M |
| DR-7 | Rider earnings view per day and week; cash collected vs owed for cash orders | M |
| DR-8 | Admin live map of riders and active orders | M |
| DR-9 | Batching two orders from the same store to one rider | C |

### 4.7 Live tracking

| ID | Requirement | P |
|---|---|---|
| TR-1 | Customer order page shows status steps, ETA and, once picked up, the rider's position on a map | M |
| TR-2 | Updates arrive within 3 s of the event while the page is open; push notifications when it is closed | M |
| TR-3 | Customer can call or message the rider through masked contact (provider adapter) | C |

### 4.8 Promotions (`promotion`)

| ID | Requirement | P |
|---|---|---|
| PR-1 | Coupon types: percent off (with cap), fixed amount off, free delivery | M |
| PR-2 | Rules: min order, valid dates, total and per-customer usage limits, first order only, specific stores or zones | M |
| PR-3 | Funding split: operator-funded, store-funded or shared (recorded in the ledger) | M |
| PR-4 | Automatic store offers shown on store cards (no code needed) | S |

### 4.9 Ratings and reviews (`review`)

| ID | Requirement | P |
|---|---|---|
| RV-1 | After delivery, customer rates the store (1 to 5, text, optional photo) and the rider (1 to 5, tags) | M |
| RV-2 | Store owner can reply publicly once | S |
| RV-3 | Admin moderation queue; profanity filter; hide with reason | M |
| RV-4 | Store rating shown as average with count; rider rating private to operator | M |

### 4.10 Notifications (`notification`)

| ID | Requirement | P |
|---|---|---|
| NO-1 | Channels: email (SMTP), SMS (provider adapter), web and mobile push (FCM) | M |
| NO-2 | Templates per event, per channel and per language, editable by admin | M |
| NO-3 | Customer notification preferences (marketing opt-in separate from transactional) | M |
| NO-4 | Delivery log with retry and failure reasons | M |

### 4.11 Finance (`ledger`)

| ID | Requirement | P |
|---|---|---|
| FI-1 | Double-entry ledger for every money event: order paid, commission, delivery fee, tip, discount funding, refund, rider earning, cash collected | M |
| FI-2 | Commission rate per store (percent and optional fixed fee), with history | M |
| FI-3 | Payout report per store and per rider for a period, as CSV and PDF; mark as settled with reference | M |
| FI-4 | Tax rates configurable per tax class; tax shown on receipts | M |
| FI-5 | Customer receipt (email and downloadable) | M |
| FI-6 | Reconciliation report: gateway settlements vs ledger | S |

### 4.12 Settings and branding (`settings`)

| ID | Requirement | P |
|---|---|---|
| SG-1 | Branding: platform name, logo (light and dark), favicon, primary and secondary colours, font family, applied at runtime to both apps | M |
| SG-2 | Locale: enabled languages, default language, currency, timezone, distance unit | M |
| SG-3 | Business rules: auto-reject timeout, service fee, cancellation policy, tip options, rider offer timeout | M |
| SG-4 | Content pages (terms, privacy, FAQ, about) editable per language | M |
| SG-5 | Optional "Powered by ilyasLabs" footer toggle | M |
| SG-6 | Feature flags for optional features (pickup, social login, reviews) | S |

### 4.13 Admin back office (cross-cutting)

| ID | Requirement | P |
|---|---|---|
| AD-1 | Dashboard: live orders, orders today, GMV, average delivery time, online riders, store acceptance rate | M |
| AD-2 | List, filter and export: orders, customers, stores, riders, reviews, coupons | M |
| AD-3 | Audit log of every back-office write (who, what, when, before and after) | M |
| AD-4 | Customer support view: customer profile, order history, notes | M |

## 5. UX principles

1. **Mobile first for customers and riders, desktop first for admin and store.** Rider UI uses large touch targets (at least 48 px), high contrast and one-hand reach.
2. **Fewest taps to order.** Address, store, item, checkout, pay. Target: a returning customer reorders in 3 taps.
3. **Always tell the customer what happens next**, with an ETA. No silent states.
4. **Store alerts are impossible to miss**: sound, vibration and flashing tab title until acknowledged.
5. **Runtime branding everywhere** through design tokens: colours, logo, font. No hard-coded colours in components.
6. **Accessible by default**: WCAG 2.2 AA, full keyboard support in the back office, screen-reader labels, reduced-motion support.
7. **RTL and i18n built in**: logical CSS properties, mirrored icons where meaningful, locale-aware numbers, dates and currency.
8. **Resilient on weak networks**: skeleton loaders, optimistic UI where safe, offline banner, rider PWA queues status updates while offline.
9. **Light and dark themes** for both apps.

### Key screens

| App | Screens |
|---|---|
| Customer web | Address picker, Home (sections and store list), Search, Store page (menu with sticky categories), Item sheet (modifiers), Cart, Checkout, Order tracking, Orders history, Rate order, Profile and addresses, Content pages |
| Back office: store | Live order board, Order detail, Menu editor, Availability, Hours and pause, Reviews, Earnings, Store settings |
| Back office: rider | Go online, Job offer, Active job (pickup, drop-off steps), Earnings, Profile |
| Back office: admin | Dashboard, Live map, Orders, Stores (onboarding wizard), Riders, Customers, Zones (map editor), Coupons, Reviews moderation, Finance (ledger, payouts), Notifications templates, Branding and settings, Users and roles, Audit log |

## 6. Non-functional requirements

| Area | Requirement |
|---|---|
| Performance | API p95 < 300 ms for reads and < 600 ms for order placement at target load; search autocomplete p95 < 150 ms; customer web LCP < 2.5 s on mid-range phone over 4G |
| Scale (reference) | Monolith: 200 orders per hour sustained. Split preset: 5,000 orders per hour with horizontal scaling of `order`, `dispatch`, `search` |
| Availability | 99.9% monthly for order placement in the reference production setup (3 replicas, multi-AZ datastores) |
| Real time | Order status and rider position reach the customer within 3 s |
| Security | OWASP ASVS L2 targets; mTLS between all pods; TLS 1.2+ at the edge; secrets never in images; PII encrypted at rest; no card data stored |
| Privacy | Data export and deletion; configurable retention for location history (default 30 days) |
| Observability | Traces, metrics and logs for every request, correlated by trace ID |
| Localisation | All user-facing strings externalised; RTL layout verified for Arabic and Urdu |
| Accessibility | WCAG 2.2 AA |
| Portability | Runs on any CNCF-conformant Kubernetes 1.31+; no cloud-specific dependency in the default chart |
| Upgrade | Rolling upgrades with backward-compatible migrations; API changes are additive within `/api/v1` |

## 7. Release plan

| Milestone | Content | Exit criteria |
|---|---|---|
| **M0 Foundation** | Repo skeleton, Maven multi-module build, Angular workspace, CI, Docker images, Helm chart with `dev` values, `identity` and `settings` modules | `helm install` brings up an empty branded platform; admin can log in |
| **M1 Catalogue** | `store`, `geo`, `search`; store onboarding, menus, zones; customer browse and search | Customer finds a store and sees its menu in two languages, one RTL |
| **M2 Ordering** | `cart`, `order`, `payment` (cash and card adapter), `promotion`, `notification` | Customer places and pays for an order; store accepts and completes a pickup order |
| **M3 Delivery** | `dispatch`, `rider`, live tracking, rider PWA, admin live map | End-to-end delivery with rider and live map |
| **M4 Money and trust** | `ledger`, payout reports, `review`, audit log, data export and deletion | Ledger balances for every test order; payout CSV reconciles |
| **M5 Production hardening** | `split` preset, Istio ambient mTLS, observability chart, load tests, security review, docs and quick start | NFR targets met in load test; quick start verified on a clean cluster |
| **v1.0 GA** | Release to Maven Central, Docker Hub, GHCR | Tagged release, changelog, upgrade notes |

## 8. Out of scope for v1

See [BRD section 7.2](BRD.md#72-out-of-scope-for-v1-candidates-for-later).

## 9. Open questions

1. Card adapter to ship first: one global gateway (recommended) or a regional one for the first pilot market.
2. SMS provider adapter to ship first.
3. Rider earning formula defaults (base + per-km + per-minute waiting) and whether operators can configure it per zone in v1.
