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
| `/rider` | `RIDER` (operator rider) or `STORE_RIDER` (scoped to one store) |

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
- On accepting a delivery order, the store chooses **who delivers**: its own riders, or an operator rider (offered only when the operator rider service is on). Operator dispatch starts right away so the rider arrives around `READY`; a store's own riders get the job in the rider app, or the store marks `PICKED_UP` and `DELIVERED` itself.
- Every transition is timestamped, attributed to an actor, and visible on the order timeline.

## 4. Functional requirements

Priority: **M** = must for v1, **S** = should for v1, **C** = could (later).

### 4.1 Identity and accounts (`identity`)

| ID | Requirement | P |
|---|---|---|
| ID-1 | Customer sign-up and login by **email and password** or **email one-time code**. A phone number is collected at checkout so the rider can call (not verified in v1). Phone login arrives with a future SMS adapter | M |
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
| ST-1 | Admin onboards a store: legal name, display name, logo, cover image, address and location, cuisines, tax ID, commission (percentage, fixed amount per order, or both), pickup enabled, payment collection (operator, or direct when allowed, see 4.14) | M |
| ST-2 | The **store owner** sets opening hours per weekday (several time ranges per day allowed) and holiday closures; within those hours the store receives orders automatically | M |
| ST-2a | **Temporarily closed** safety switch: the store owner turns the store off at any time; it shows as "Temporarily closed" and takes no orders **until the owner turns it back on**, whatever the timetable says. An optional reason is shown to customers. Admins can see and override it | M |
| ST-3 | Menu: categories, items (name, description, photos, price, tax class, dietary tags, allergens), modifier groups (required or optional, min and max choices, price deltas) | M |
| ST-4 | Item and modifier availability toggles (sold out today) | M |
| ST-5 | All menu text translatable per configured language | M |
| ST-6 | Store owner edits menu; changes to price can be set to require admin approval | S |
| ST-7 | Average preparation time per store, used for ETA | M |
| ST-8 | Bulk menu import from CSV | S |
| ST-9 | Store groups (one owner, many branches) | S |
| ST-10 | **Own riders:** the store owner invites their own riders, who use the same rider app and see only that store's jobs | M |
| ST-11 | **Own delivery zones:** the store owner draws delivery zones on a map, each with a **fixed delivery charge** (no per-km calculation; the owner knows the distance of each area, so typical setups are rings such as 0-3 km and 3-6 km), minimum order and optional free-delivery threshold | M |
| ST-12 | **QR menu:** the store owner prints a QR code (A4/A5/table-tent PDF, store branding) that opens the store's menu in the customer web app; an optional table number is carried in the link for later dine-in ordering | M |

### 4.3 Discovery and search (`search`, `geo`)

| ID | Requirement | P |
|---|---|---|
| SE-1 | Home shows stores that deliver to the chosen address, ordered by relevance (open now, distance, rating, ETA) | M |
| SE-2 | Full-text search across stores, cuisines and dishes, typo-tolerant, in every configured language | M |
| SE-3 | Filters: cuisine, rating, delivery fee, ETA, dietary tags, pickup available, offers | M |
| SE-4 | Autocomplete suggestions under 150 ms p95 | M |
| SE-5 | Admin-curated home sections (banners, featured stores, collections) | S |
| SE-6 | Address search and reverse geocoding on the map pin | M |
| SE-7 | Delivery zones are drawn **only by stores** (ST-11). There are no operator zones, so one delivery price applies whoever delivers. Admins can view any store's zones and correct them for support | M |

### 4.4 Cart and checkout (`cart`, `order`, `promotion`, `payment`)

| ID | Requirement | P |
|---|---|---|
| CO-1 | One cart per customer, one store per cart; switching store asks to clear | M |
| CO-2 | Checkout shows itemised subtotal, delivery fee, service fee, discounts, tax, tip (optional), total | M |
| CO-3 | Choose delivery or pickup; the address must be inside one of the store's delivery zones, and the delivery charge comes from that zone. It is fixed at checkout and is the same whoever delivers (see OF-8) | M |
| CO-4 | Apply one coupon per order; validation explains why a coupon fails | M |
| CO-5 | Pay by cash on delivery or by card through the configured gateway. The operator can **switch card payments off** entirely, and choose the integration style: the gateway's **hosted payment page** (redirect, recommended at launch) or embedded gateway fields | M |
| CO-5a | **Saved cards** (gateway tokens) are a separate setting, **off by default** | M |
| CO-10 | For a store that collects payments directly (see 4.14), card payment goes to the store's own merchant account; the customer experience is the same and the receipt names the store as the merchant | M |
| CO-6 | Price and availability re-validated at order placement; customer is told about any change | M |
| CO-7 | Idempotent order placement (double tap never creates two orders) | M |
| CO-8 | ETA shown before ordering and updated live after | M |
| CO-9 | Re-order from history in one tap | S |

### 4.5 Order fulfilment (`order`) and store experience

| ID | Requirement | P |
|---|---|---|
| OF-1 | Store order board (new, preparing, ready, out) updates live with a loud, repeating alert for new orders | M |
| OF-2 | Store accepts with a prep-time estimate or rejects with a reason | M |
| OF-8 | When accepting a delivery order, the store chooses **deliver myself** (and optionally assigns one of its own riders) or **request operator rider**. The second option is shown only when the operator rider service is on and the store is allowed to use it. The customer's delivery charge always belongs to the store. When an operator rider delivers, the ledger bills the store the operator rider fee (DR-1b) | M |
| OF-3 | Store marks preparing and ready; prints a kitchen ticket (browser print) | M |
| OF-4 | For self-delivered orders, the store's rider (or the store) marks picked up and delivered | M |
| OF-5 | Customer can cancel before acceptance; after that, only support can cancel | M |
| OF-6 | Support sees the full timeline and can cancel, refund fully or partly, or reassign a rider, always with a reason | M |
| OF-7 | Store dashboard: today's orders, revenue, average prep time, rating | S |

### 4.6 Dispatch and riders (`dispatch`, `rider`)

| ID | Requirement | P |
|---|---|---|
| DR-1 | Operator rider onboarding by admin: identity documents, vehicle type, city, payout details. Store riders are invited by the store owner and belong only to that store | M |
| DR-1a | The **operator rider service** is a global setting, off by default, so a new operator can launch with stores delivering themselves | M |
| DR-1b | The operator sets the **operator rider fee** billed to a store for each delivery by an operator rider: a fixed amount plus an optional amount per km, with an optional override per store | M |
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
| PR-2 | Rules: min order, valid dates, total and per-customer usage limits, first order only, specific stores or cities | M |
| PR-3 | Promotions are created by the **operator** (any stores or cities) or by a **store owner** (own store only, store-funded); operator promotions can be operator-funded or shared with participating stores (recorded in the ledger) | M |
| PR-3a | An operator promotion can be restricted to **payment methods** (for example card only, or cash only) and to **payments collected on the operator's merchant account**, so it does not apply to orders paid into a store's own account | M |
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
| NO-1 | Channels: **in-app notification centre** (bell with unread count in both apps), email (SMTP), web and mobile push (FCM). SMS is out of scope for v1; the channel interface allows adding it later | M |
| NO-2 | Templates per event, per channel and per language, editable by admin | M |
| NO-3 | Customer notification preferences (marketing opt-in separate from transactional) | M |
| NO-4 | Delivery log with retry and failure reasons | M |

### 4.11 Finance (`ledger`)

| ID | Requirement | P |
|---|---|---|
| FI-1 | Double-entry ledger for every money event: order paid, commission, delivery fee, tip, discount funding, refund, rider earning, cash collected | M |
| FI-2 | Commission per store as a percentage, a fixed amount per order, or both, with effective dates and history | M |
| FI-3 | Payout report per store and per rider for a period, as CSV and PDF; mark as settled with reference | M |
| FI-4 | Tax rates configurable per tax class; tax shown on receipts | M |
| FI-5 | Customer receipt (email and downloadable) | M |
| FI-6 | Reconciliation report: gateway settlements vs ledger | S |
| FI-7 | Per-store balance showing both directions: what the operator owes the store (operator-collected orders) and what the store owes the operator (direct-collected orders, store-collected cash). Settlement reports show the **net** amount and who pays whom | M |

### 4.12 Settings and branding (`settings`)

| ID | Requirement | P |
|---|---|---|
| SG-1 | Branding: platform name, logo (light and dark), favicon, primary and secondary colours, font family, applied at runtime to both apps | M |
| SG-2 | Locale: enabled languages, default language, currency, timezone, distance unit | M |
| SG-3 | Business rules: auto-reject timeout, service fee, cancellation policy, tip options, rider offer timeout | M |
| SG-4 | Content pages (terms, privacy, FAQ, about) editable per language | M |
| SG-5 | Optional "Powered by ilyasLabs" footer toggle | M |
| SG-6 | Feature flags for optional features (pickup, social login, reviews, operator rider service, card payments, saved cards, store direct payment collection) | M |

### 4.13 Admin back office (cross-cutting)

| ID | Requirement | P |
|---|---|---|
| AD-1 | Dashboard: live orders, orders today, GMV, average delivery time, online riders, store acceptance rate | M |
| AD-2 | List, filter and export: orders, customers, stores, riders, reviews, coupons | M |
| AD-3 | Audit log of every back-office write (who, what, when, before and after) | M |
| AD-4 | Customer support view: customer profile, order history, notes | M |

### 4.14 Store direct payment collection (optional)

By default the operator collects every card payment and pays stores later. The operator can instead let a store collect card payments straight into the store's own merchant account.

| ID | Requirement | P |
|---|---|---|
| DP-1 | A global setting enables the feature; it is **off** by default | M |
| DP-2 | Only an admin with the `finance` permission can allow or revoke direct collection **for an individual store**; every change is audit-logged with a reason | M |
| DP-3 | Once allowed, the store owner connects their own merchant account in the store area (gateway credentials or the gateway's account-connect flow), using one of the gateway adapters the operator has enabled. Credentials are stored encrypted and never shown again | M |
| DP-4 | The connection is verified (test call) before it becomes active; until then, and whenever it fails, card payments fall back to the operator's account | M |
| DP-5 | For direct-collected orders the ledger records the full amount as collected by the store and creates a **receivable** from the store for commission, operator rider fee (when the operator's riders deliver), service fee, and rider tips; store-funded and operator-funded discounts are accounted for as for any order | M |
| DP-6 | Refunds of direct-collected orders are issued through the store's merchant account; the receivable is adjusted to match | M |
| DP-7 | The operator sets a **credit limit** per store (default from settings). When the outstanding balance passes it, direct collection is automatically suspended (payments go back to the operator's account) and the store and admins are notified; it resumes when the balance is settled | M |
| DP-8 | Store dashboard and admin finance view show the outstanding balance, due date and history; admins record received payments against it | M |
| DP-9 | Gateway-native split payments (the gateway splits each payment between store and operator automatically) as a later option, to remove the receivable entirely | C |

### 4.15 Media (`media`)

| ID | Requirement | P |
|---|---|---|
| ME-1 | Images (store logos and covers, menu photos, review photos, rider documents, branding assets) are uploaded straight to S3-compatible storage with short-lived signed URLs | M |
| ME-2 | On upload, images are validated (type, size), stripped of metadata, resized to fixed variants (thumbnail, card, detail) and stored as WebP (AVIF where supported); the original is kept only when an admin setting asks for it | M |
| ME-3 | Public images are served with long cache lifetimes so a CDN can sit in front; private documents (rider IDs) are served only through short-lived signed URLs | M |
| ME-4 | Images no longer referenced are cleaned up by a scheduled job | S |

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
| Customer web | Address picker, Home (sections and store list), Search, Store page (menu with sticky categories), Item sheet (modifiers), Cart, Checkout, Order tracking, Orders history, Rate order, Profile and addresses, Notification centre, In-store menu (from QR), Content pages |
| Back office: store | Live order board, Order detail (with delivery choice), Menu editor, Availability, Opening hours and temporary close, Delivery zones and charges (map), Riders, QR menu print, Reviews, Earnings and balance, Payment account (when direct collection is allowed), Store settings |
| Back office: rider | Go online, Job offer (operator or store jobs), Active job (pickup, drop-off steps), Earnings, Profile |
| Back office: admin | Dashboard, Live map, Orders, Stores (onboarding wizard), Riders, Customers, Store delivery zones (view and correct), Operator rider fee, Coupons, Reviews moderation, Finance (ledger, payouts), Notifications templates, Branding and settings, Users and roles, Audit log |

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
| Portability | Runs on one Linux server with Docker through the unit deployer; optionally on any CNCF-conformant Kubernetes 1.31+; no cloud-specific dependency |
| Media | Images resized to fixed variants and stored as WebP (AVIF where supported); originals over 10 MB rejected; typical menu photo under 80 KB |
| Time | Stored in UTC; API requests and responses carry date-times as UTC epoch seconds |
| Developer setup | Clone and run the server from the IDE or one Maven command with Testcontainers starting every dependency; `test-seed` profile loads demo data |
| Upgrade | Rolling upgrades with backward-compatible migrations; API changes are additive within `/api/v1` |

## 7. Release plan

| Milestone | Content | Exit criteria |
|---|---|---|
| **M0 Foundation** | Repo skeleton, Maven multi-module build, Angular workspace, CI, Docker images, Testcontainers local run and `test-seed` seeder, **unit deployer**, `identity`, `settings` and `media` modules | `install.sh` from the unit deployer brings up an empty branded platform on a clean server; admin can log in |
| **M1 Catalogue** | `store`, `geo`, `search`; store onboarding, menus, store delivery zones; customer browse and search | Customer finds a store and sees its menu in two languages, one RTL |
| **M2 Ordering** | `cart`, `order`, `payment` (cash and card adapter), `promotion`, `notification` | Customer places and pays for an order; store accepts and completes a pickup order |
| **M3 Delivery** | `dispatch`, `rider`, live tracking, rider PWA, admin live map | End-to-end delivery with rider and live map |
| **M4 Money and trust** | `ledger`, payout reports with netting, store direct payment collection, `review`, audit log, data export and deletion | Ledger balances for every test order; payout CSV reconciles |
| **M5 Production hardening** | Optional Kubernetes path: Helm chart, `split` preset, Istio ambient mTLS, observability chart; load tests, security review, docs and quick start | NFR targets met in load test; quick start verified on a clean server and on a clean cluster |
| **v1.0 GA** | Release to Maven Central, Docker Hub, GHCR | Tagged release, changelog, upgrade notes |

## 8. Out of scope for v1

See [BRD section 7.2](BRD.md#72-out-of-scope-for-v1-candidates-for-later).

## 9. Open questions

1. Card adapter to ship first: one global gateway (recommended) or a regional one for the first pilot market.
2. Rider earning formula defaults (base + per-km + per-minute waiting) and whether operators can configure it per city in v1.
