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
| BO-1 | Time to launch | A new operator goes from `helm install` to a branded, working marketplace in **under 1 day** with the quick-start guide |
| BO-2 | No team needed to start | Operator launches without code changes: branding, currency, language, commission and zones are all configuration |
| BO-3 | Low cost at small scale | Monolith mode runs a city pilot (≤ 200 orders per hour) on a 3-node cluster plus managed datastores |
| BO-4 | Scale without rewrite | Any module can move to its own deployment by Helm values only, proven by the `split` preset in CI |
| BO-5 | Trust and safety | No critical or high open security findings at release; all service-to-service traffic encrypted (mTLS) |
| BO-6 | Money is traceable | Every order produces balanced ledger entries; payout reports reconcile to the cent |
| BO-7 | Community adoption | Public docs, quick start, contribution guide; first external operator pilot after v1 |

## 7. Scope

### 7.1 In scope for v1 (decided in grooming)

| Area | Decision |
|---|---|
| Deployment model | **One operator per deployment**, running its own branded marketplace with many stores (white-label). Not multi-tenant SaaS |
| Verticals | **Restaurants**, modelled as a generic *store* so grocery or pharmacy can be added later without a rewrite |
| Delivery | Operator's **own rider fleet**, plus a per-store **"delivers itself"** option |
| Fulfilment types | **Delivery** and **pickup** |
| Front-ends | **Customer web app** and **one back-office app** with role-based areas for admin, store and rider (rider area installable as a PWA). All Angular |
| API | Pure REST, so native mobile apps can be built later on the same API |
| Payments | **Cash on delivery** and a pluggable gateway interface with **one card adapter** |
| Money | **Commission per store**, a **ledger** for every order, **payout reports** exported for manual settlement |
| Engagement | **Coupons**, **ratings and reviews**, **notifications** (email, SMS, push) |
| Localisation | **Multi-language with RTL**, one currency and timezone per deployment (config) |
| Branding | Name, logo, colours and fonts set from the admin panel **at runtime**, without a rebuild |
| Operations | Docker images, Helm chart with service mesh (mTLS), observability stack, CI/CD |

### 7.2 Out of scope for v1 (candidates for later)

- Multi-tenant SaaS (many operators in one deployment)
- Grocery and pharmacy specifics (inventory by SKU, prescriptions, substitutions)
- Third-party logistics adapters
- Native iOS and Android apps
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
| BR-2 | Stores can be onboarded by the operator, manage their own menu, hours and availability, and accept, prepare and hand over orders | Must |
| BR-3 | Riders can go online, receive and accept delivery jobs, navigate to pickup and drop-off, and confirm delivery | Must |
| BR-4 | Operator admins can manage stores, riders, customers, delivery zones, fees, commissions, coupons, content and branding | Must |
| BR-5 | Each order records commission, fees, taxes and rider earnings in a ledger; admins export payout reports per period | Must |
| BR-6 | Customers, stores and riders receive notifications for every order state change that affects them | Must |
| BR-7 | Customers can rate and review stores and riders after delivery; admins can moderate | Must |
| BR-8 | Operators can brand the platform and choose language, currency and timezone without code changes | Must |
| BR-9 | The platform can be deployed to any conformant Kubernetes cluster with one Helm chart, secure by default | Must |
| BR-10 | Any backend module can be split into its own service by configuration only | Must |
| BR-11 | All personal data handling supports data export and deletion requests (GDPR-style) | Must |
| BR-12 | A REST API, documented with OpenAPI, covers every capability of the web apps | Must |
| BR-13 | Support staff can view any order's timeline and issue refunds or cancellations with a reason | Should |
| BR-14 | Operators can run the platform in several cities, each with its own delivery zones | Should |

## 9. Assumptions

- The operator provides: a domain, a Kubernetes cluster, managed MongoDB, Redis, Kafka and Elasticsearch (or uses the in-cluster `dev` setup for trials), an SMTP server, an SMS provider account, a push (FCM) project, and a payment gateway account.
- The operator handles its own legal entity, tax registration, food-safety compliance and rider employment or contracting terms. FoodStack supplies configurable tax rates and invoices but not legal advice.
- Default maps use OpenStreetMap-based services; operators with high volume either self-host them or switch to a commercial maps adapter.

## 10. Constraints

- Backend: Java 25, Spring Boot 4, built on the **Module-Switch** library so every module can run in-process, remotely, or off.
- Data: MongoDB only for persistence, with every collection **prefixed by its module name**; Redis for caching; Elasticsearch for search; Kafka for events; Liquibase migrations per module.
- Front-end: Angular (no micro-frontends).
- Real company names of existing delivery platforms must never appear in code or documentation.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Infrastructure footprint (Mongo, Redis, Kafka, Elasticsearch) is heavy for a small pilot | Higher running cost | `dev` profile runs everything in-cluster with small sizing; docs list managed-service options and minimum sizes |
| Third-party licence changes (Liquibase FSL, Elasticsearch AGPL/ELv2) | Legal uncertainty for operators | Operators run unmodified upstream binaries; search behind an interface so an OpenSearch adapter is possible; licences documented in `docs/` |
| Revenue share relies on self-reporting | Lost revenue for author | Clear licence notice in README, admin "About" page, and release notes |
| Payment and personal data | Regulatory exposure | No card data stored (gateway tokens only), PII encryption at rest, data-retention settings |
| Rider supply is a marketplace cold-start problem | Slow operator launch | Per-store self-delivery option so stores can go live before a fleet exists |

## 12. Glossary

| Term | Meaning |
|---|---|
| Operator | The business that deploys and runs a FoodStack marketplace |
| Store | A seller on the marketplace; a restaurant in v1 |
| Zone | A geographic polygon where delivery is offered, with its own fees |
| Dispatch | Assigning a delivery job to a rider |
| Ledger | Double-entry record of every money movement |
| Module | A bounded part of the backend (for example `order`) that can run in-process, remotely or off via Module-Switch |
