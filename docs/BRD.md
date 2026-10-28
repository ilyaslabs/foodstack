# FoodStack: Business Requirements Document (BRD)

| | |
|---|---|
| Status | Draft v1, agreed in grooming with ilyas on 2026-10-27 |
| Owner | ilyas (product owner, decision maker) |
| Related | [PRD](PRD.md), [TDD](TDD.md) |
| License | [Fair Revenue Share License v1](https://ilyaslabs.github.io/docs/licenses/fair-revenue-v1) |

## 1. Purpose

FoodStack is an open-source, production-ready food ordering and delivery platform. Someone with capital and a local market can launch their own branded delivery marketplace on day one, without hiring a tech team to build it first.

This document states the business problem, who FoodStack serves, what it must achieve, and what is in and out of scope for v1. Product behaviour is in the [PRD](PRD.md); technical design is in the [TDD](TDD.md).

## 2. Problem

Launching a food delivery marketplace today means either:

- building a platform from scratch: a 12 to 24 month effort with a team of 10 or more engineers before the first order is taken; or
- renting a closed white-label SaaS: recurring fees, no ownership of data or code, and no way to extend it.

Both lock money and time away from what actually decides success in this business: signing up restaurants, recruiting riders and acquiring customers.

## 3. Vision

> Download it, configure it, brand it, and take your first order in days, not years.

FoodStack gives an operator the full stack (customer app, back office, store and rider tools, REST API, Kubernetes deployment) as open source. It starts as a single deployable unit that is cheap to run, and grows into independently scaled services by changing configuration only, never code.

## 4. Business model and licensing

- FoodStack is released under the **Fair Revenue Share License v1**: free for personal, educational and commercial use until the operator's project passes **USD 10,000 total revenue**, after which **5% of monthly gross revenue** is shared with the author. Copyright notices must be kept and the code may not be relicensed to remove the revenue share.
- Artifacts are public: jars on Maven Central, images on Docker Hub (mirrored to GHCR), Helm chart in the repository.
- Attribution ("Powered by ilyasLabs") is appreciated but not mandatory. The product UI exposes an optional footer toggle for it.

## 5. Stakeholders

| Stakeholder | Interest |
|---|---|
| **Operator** (the business that deploys FoodStack) | Launch fast, own brand and data, keep costs proportional to traffic |
| **Store owner / staff** (restaurant; later grocery, pharmacy) | Receive and fulfil orders, manage menu, see earnings |
| **Rider** | Receive delivery jobs, navigate, get paid fairly |
| **Customer** | Find food, order easily, pay safely, track delivery |
| **Operator's admin / support team** | Onboard stores and riders, resolve issues, run promotions, settle money |
| **Operator's DevOps** | Deploy securely, observe, scale, upgrade |
| **Open-source contributors** | Clear module boundaries, good docs, easy local setup |
| **ilyasLabs** (author) | Adoption, sustainability through revenue share |

## 6. Business objectives and success measures

| # | Objective | Measure (target for v1) |
|---|---|---|
| BO-1 | Time to launch | A new operator goes from unzipping the unit deployer on one server to a branded, working marketplace in **under 1 day** with the quick-start guide |
| BO-2 | No team needed to start | Operator launches without code changes: branding, currency, language, commission and the operator rider fee are all configuration |
| BO-3 | Low cost at small scale | The unit deployer runs a city pilot (≤ 200 orders per hour) on **one server** (8 vCPU, 32 GB RAM reference) with every dependency included |
| BO-4 | Scale without rewrite | Moving from one server to Kubernetes, and splitting any module into its own deployment, needs configuration only; proven by the `split` preset in CI |
| BO-5 | Trust and safety | No critical or high open security findings at release; all service-to-service traffic encrypted (mTLS) |
| BO-6 | Money is traceable | Every order produces balanced ledger entries; payout reports reconcile to the cent |
| BO-7 | Community adoption | Public docs, quick start, contribution guide; first external operator pilot after v1 |

## 7. Scope

### 7.1 In scope for v1 (decided in grooming)

| Area | Decision |
|---|---|
| Deployment model | **One operator per deployment**, running its own branded marketplace with many stores (white-label). Not multi-tenant SaaS |
| Verticals | **Restaurants**, modelled as a generic *store* so grocery or pharmacy can be added later without a rewrite |
| Delivery | **Stores deliver with their own riders** by default. The operator can switch on an **operator rider service**; then, after accepting each order, the store chooses to deliver itself or request an operator rider. Delivery zones and charges are set **only by the store**, so the customer sees one delivery price whoever delivers. When an operator rider delivers, the operator bills the store an **operator rider fee** that the operator sets; the customer never sees it |
| Fulfilment types | **Delivery** and **pickup** |
| Front-ends | **Customer web app** and **one back-office app** with role-based areas for admin, store and rider (rider area installable as a PWA). All Angular |
| API | Pure REST, so native mobile apps can be built later on the same API |
| Payments | **Cash on delivery** and a pluggable gateway interface with **one card adapter**. Card payments can be switched off entirely, or taken on the gateway's **hosted payment page**; saved cards are optional and off by default |
| Money | **Commission per store** as a percentage, a fixed amount per order, or both; a **ledger** for every order, **payout reports** exported for manual settlement |
| Payment collection | By default the **operator collects** card payments and pays stores later. As an option the operator can **allow an individual store to collect card payments directly** into the store's own merchant account; FoodStack then records what the store owes the operator (commission, fees) and nets it in settlement |
| Engagement | **Promotions and coupons** by the operator or by a store, **ratings and reviews**, **notifications** (in-app, email, push) |
| In-store | Printable **QR code** per store that opens its menu, for customers at the table (view only in v1) |
| Localisation | **Multi-language with RTL**, one currency and timezone per deployment (config) |
| Branding | Name, logo, colours and fonts set from the admin panel **at runtime**, without a rebuild |
| Operations | **Unit deployer** for launch: one zip (`unit-deployer-<version>.zip`) with the application, a Docker Compose file and install, start, stop and restart scripts that run everything, including MongoDB, Kafka, Redis, Elasticsearch and object storage, on one server. **Kubernetes** Helm chart with service mesh (mTLS) is optional, for when the operator outgrows one server. Docker images, observability, CI/CD |
| Media | Images stored in S3-compatible object storage, resized and compressed on upload to keep storage small |
| Developer experience | One-click local run with Testcontainers, plus demo data when the `test-seed` profile is active |

### 7.2 Out of scope for v1 (candidates for later)

- Multi-tenant SaaS (many operators in one deployment)
- Grocery and pharmacy specifics (inventory by SKU, prescriptions, substitutions)
- Third-party logistics adapters
- Native iOS and Android apps
- SMS notifications and phone-number OTP login (an SMS adapter can be added later)
- Ordering from the table (dine-in); the QR code only shows the menu in v1
- Customer wallet, loyalty points, subscriptions
- Scheduled (future-time) orders, group orders, dine-in
- Automated payouts to stores and riders
- Multi-currency within one deployment
- Dynamic surge pricing and ML-based dispatch optimisation
- Ads and sponsored listings

## 8. High-level business requirements

| ID | Requirement | Priority |
|---|---|---|
| BR-1 | Customers can discover nearby open stores, browse menus, order for delivery or pickup, pay by cash or card, and track the order live | Must |
| BR-2 | Stores can be onboarded by the operator, manage their own menu, opening hours, temporary closure, delivery zones and charges, own riders and QR menu, and accept, prepare and hand over orders | Must |
| BR-3 | Riders (the operator's, or a store's own) can go online, receive and accept delivery jobs, navigate to pickup and drop-off, and confirm delivery | Must |
| BR-4 | Operator admins can manage stores, riders, customers, the operator rider fee, service fees, commissions, coupons, content and branding | Must |
| BR-5 | Each order records commission, fees, taxes and rider earnings in a ledger; admins export payout reports per period | Must |
| BR-6 | Customers, stores and riders receive notifications for every order state change that affects them | Must |
| BR-7 | Customers can rate and review stores and riders after delivery; admins can moderate | Must |
| BR-8 | Operators can brand the platform and choose language, currency and timezone without code changes | Must |
| BR-9 | The platform can be installed on a single server with the unit deployer, and optionally on any conformant Kubernetes cluster with one Helm chart, secure by default | Must |
| BR-10 | Any backend module can be split into its own service by configuration only | Must |
| BR-11 | All personal data handling supports data export and deletion requests (GDPR-style) | Must |
| BR-12 | A REST API, documented with OpenAPI, covers every capability of the web apps | Must |
| BR-13 | Support staff can view any order's timeline and issue refunds or cancellations with a reason | Should |
| BR-14 | Operators can run the platform in several cities, with stores, riders and reports grouped by city | Should |
| BR-15 | The operator can allow an individual store to receive card payments directly into its own merchant account. Only the operator can grant or revoke this, per store. For such orders the ledger records what the store owes the operator, and settlement nets it against what the operator owes the store | Must |

## 9. Assumptions

- The operator provides: a domain, one Linux server with Docker (or a Kubernetes cluster with managed MongoDB, Redis, Kafka, Elasticsearch and S3-compatible storage), an SMTP server, a push (FCM) project, and a payment gateway account if cards are accepted.
- The operator handles its own legal entity, tax registration, food-safety compliance and rider employment or contracting terms. FoodStack supplies configurable tax rates and invoices but not legal advice.
- Default maps use OpenStreetMap-based services; operators with high volume either self-host them or switch to a commercial maps adapter.

## 10. Constraints

- Backend: Java 25, Spring Boot 4, built on the **Module-Switch** library so every module can run in-process, remotely, or off.
- Data: MongoDB only for persistence, with every collection **prefixed by its module name** and designed for sharding; Redis for caching; Elasticsearch for search; Kafka for events; S3-compatible storage for media; Liquibase migrations per module.
- Time: stored in UTC; exchanged in the API as UTC epoch seconds.
- Front-end: Angular (no micro-frontends).
- Real company names of existing delivery platforms must never appear in code or documentation.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Infrastructure footprint (Mongo, Redis, Kafka, Elasticsearch) is heavy for a small pilot | Higher running cost | `dev` profile runs everything in-cluster with small sizing; docs list managed-service options and minimum sizes |
| Third-party licence changes (Liquibase FSL, Elasticsearch AGPL/ELv2) | Legal uncertainty for operators | Operators run unmodified upstream binaries; search behind an interface so an OpenSearch adapter is possible; licences documented in `docs/` |
| Revenue share relies on self-reporting | Lost revenue for author | Clear licence notice in README, admin "About" page, and release notes |
| Stores collecting payments directly may not pay what they owe | Lost commission for the operator | Off by default and granted per store by the operator; outstanding balance shown on dashboards; configurable credit limit after which direct collection is suspended automatically; dues netted against any amount the operator owes the store |
| Payment and personal data | Regulatory exposure | No card data stored (gateway tokens only), PII encryption at rest, data-retention settings |
| Rider supply is a marketplace cold-start problem | Slow operator launch | Stores deliver with their own riders by default; the operator rider service is switched on only when a fleet exists |

## 12. Glossary

| Term | Meaning |
|---|---|
| Operator | The business that deploys and runs a FoodStack marketplace |
| Store | A seller on the marketplace; a restaurant in v1 |
| Zone | A geographic polygon drawn by a store where it delivers, with its delivery charge |
| Dispatch | Assigning a delivery job to a rider |
| Ledger | Double-entry record of every money movement |
| Module | A bounded part of the backend (for example `order`) that can run in-process, remotely or off via Module-Switch |
