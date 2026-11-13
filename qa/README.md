# Manual QA

Step-by-step test cases for the whole application. If you follow every file in this folder in order, every screen, role and business rule of FoodStack has been tried by hand at least once.

Each case says what to do and what you should see. Most cases also say what should now be stored in the database, so a tester can confirm the right record was written, not only the right screen shown.

Keep these files in step with the code. When a milestone changes a flow, it updates the matching file here in the same pull request.

## Files

Work through the files in this order. Later files use the stores, users and orders made in earlier ones.

| File | Area | Cases |
|---|---|---|
| [01-authentication.md](01-authentication.md) | Sign-up, sign-in (password, emailed code, Google, Apple), password reset, sessions, two-step sign-in | AUTH |
| [02-users-and-roles.md](02-users-and-roles.md) | Back-office users, roles and what each role may see, blocking, audit log | USER |
| [03-platform-settings.md](03-platform-settings.md) | Branding, languages, ordering, rider pay, delivery pricing, cities, messages, email log | SET |
| [04-stores-and-menus.md](04-stores-and-menus.md) | Brands and branches, owners, menu, prices and approvals, sold out, zones, hours, going live | STORE |
| [05-search-and-browsing.md](05-search-and-browsing.md) | Customer home page, search, store page, delivery quote, address lookup, saved addresses | SRCH |
| [06-cart-and-ordering.md](06-cart-and-ordering.md) | Cart, checkout, placing orders, the store's live board, cancel, reject, timeouts | ORD |
| [07-payments.md](07-payments.md) | Card payments (hosted and embedded), failures, expiry, refunds, saved cards, stores that collect themselves | PAY |
| [08-coupons.md](08-coupons.md) | Operator and store coupons, limits, give-back on cancel | CPN |
| [09-delivery-and-riders.md](09-delivery-and-riders.md) | Riders, papers, offers, pickup, delivery code, live tracking, dispatch support, store riders, earnings | DLV |
| [10-notifications.md](10-notifications.md) | In-app bell, emails, preferences, push, message editor | NTF |
| [11-reviews.md](11-reviews.md) | Rating stores and riders, replies, moderation | REV |
| [12-finance.md](12-finance.md) | Ledger, payouts, settlements, credit limits, exports | FIN |
| [13-privacy.md](13-privacy.md) | Data export, account deletion | PRIV |
| [14-operations.md](14-operations.md) | Install, health, failed events, search rebuild, backup and restore (also onto a new server), upgrade, monitoring, sharding existing data | OPS |
| [15-regression-pass.md](15-regression-pass.md) | A one-hour pass over the most important cases, for every release | — |

## The test environment

Use the **local run**: it has demo stores, demo users of every role, a pretend card gateway, and Mailpit to catch every email. Nothing it sends leaves your computer.

```bash
# 1. Server, with every datastore in containers (needs Docker)
mvn -pl app/foodstack-server spring-boot:test-run
# 2. In a second terminal: the back office, http://localhost:4300/admin/
cd frontend && pnpm install && pnpm start:backoffice
# 3. In a third terminal: the customer app, http://localhost:4200/
cd frontend && pnpm start:web
```

When the server is up, it prints these lines. Keep them; the technical checks use them.

```
Emails sent by this run: http://localhost:<port>
MongoDB shell: docker exec -it <container> mongosh --port 27017
Redis shell: docker exec -it <container> redis-cli
Kafka: localhost:<port> (container <container>)
Elasticsearch: http://localhost:<port> (user elastic, password foodstack-test)
```

The containers are new on every start, so every run starts with the same demo data. A case that needs a clean state says so.

[14-operations.md](14-operations.md) tests a real install (Docker Compose, and Helm on Kubernetes) separately.

### Test accounts

Every password is `foodstack-demo`.

| Email | Role | Use it for |
|---|---|---|
| `admin@demo.foodstack.local` | Administrator | Everything in the back office |
| `finance@demo.foodstack.local` | Finance | Payouts, settlements, store payment settings |
| `support@demo.foodstack.local` | Support | Orders, dispatch, review moderation |
| `customer@demo.foodstack.local` | Customer | Ordering in the customer app |
| `spiceroute@demo.foodstack.local` | Brand owner, Spice Route (two branches) | The store side of a brand |
| `napoli@demo.foodstack.local` | Branch manager, Napoli Slice (DHA) | The store side of one branch |
| `rider@demo.foodstack.local` | Operator rider (Bilal Ahmed, motorbike) | Deliveries |
| `rider2@demo.foodstack.local` | Operator rider (Ayesha Noor, scooter) | A second rider for offers |
| `napoli.rider@demo.foodstack.local` | Napoli Slice's own rider | Store delivery |
| `spiceroute.rider@demo.foodstack.local` | Spice Route's rider | Store delivery across branches |

In the local run, two-step sign-in is optional for everyone, so these accounts can be shared. On a real install, ADMIN and FINANCE must set it up at their first sign-in (AUTH-20).

The rider pages are part of the back office (`/admin/rider`). Open them on a phone, or in the browser's phone view (F12, then the device toolbar).

## How to read a case

```
### ORD-03 Place a cash order for delivery

Who: customer@demo.foodstack.local · Where: customer app

Steps
1. …

Expect
- What the screen shows.

Check
- foodstack_order.order_orders: the stored record and the values to look for.
- Kafka foodstack.order.events: the events that should appear.
```

- **Who** is the account to sign in with. **Where** is the app: the *customer app* (`localhost:4200`), the *back office* (`localhost:4300/admin`) or the *rider pages* (back office, `/admin/rider`).
- **Expect** is what a user can see. A case passes only when everything under Expect is true.
- **Check** is the technical part. It is optional for a quick pass and required for a release sign-off.

Write down each case's result (pass, fail, blocked) with the date, the build, and for a failure a screenshot and what you saw. A copy of the list in a spreadsheet works.

## How to make the technical checks

### Databases and collections

Each module has its own MongoDB database, `foodstack_<module>`, and its collections start with the module name:

| Database | Collections |
|---|---|
| `foodstack_identity` | `identity_users`, `identity_login_keys`, `identity_refresh_tokens`, `identity_addresses`, `identity_settings`, `identity_audit_log`, `identity_outbox` |
| `foodstack_settings` | `settings_settings`, `settings_dead_letters`, `settings_outbox` |
| `foodstack_media` | `media_assets`, `media_outbox` |
| `foodstack_geo` | `geo_zones`, `geo_settings`, `geo_cities`, `geo_outbox` |
| `foodstack_store` | `store_brands`, `store_stores`, `store_slugs`, `store_menu_sections`, `store_menu_items`, `store_item_overrides`, `store_price_changes`, `store_outbox` |
| `foodstack_cart` | `cart_carts`, `cart_outbox` |
| `foodstack_promotion` | `promotion_promotions`, `promotion_codes`, `promotion_customer_usage`, `promotion_customers`, `promotion_redemptions`, `promotion_outbox` |
| `foodstack_order` | `order_orders`, `order_active`, `order_placement_keys`, `order_outbox` |
| `foodstack_payment` | `payment_intents`, `payment_merchant_accounts`, `payment_store_collection`, `payment_outbox` |
| `foodstack_dispatch` | `dispatch_jobs`, `dispatch_active`, `dispatch_outbox` |
| `foodstack_rider` | `rider_riders`, `rider_onboarding`, `rider_assignments`, `rider_locations`, `rider_earnings`, `rider_outbox` |
| `foodstack_notification` | `notification_inbox`, `notification_deliveries`, `notification_preferences`, `notification_devices`, `notification_templates` |
| `foodstack_review` | `review_reviews`, `review_orders`, `review_store_stats`, `review_rider_ratings`, `review_rider_stats` |
| `foodstack_ledger` | `ledger_transactions`, `ledger_daily`, `ledger_accounts`, `ledger_orders`, `ledger_settlements`, `ledger_credit` |

Search has no database: it keeps an index in Elasticsearch, `foodstack_search_stores` (an alias).

Every database also has `<module>_inbox` (events a module has already handled, so a repeat is skipped) and Liquibase's `<module>_databasechangelog`. Ignore those unless a case names them.

Open the MongoDB shell with the command the server printed, then:

```js
use foodstack_identity
db.identity_users.find().sort({createdAt: -1}).limit(1).pretty()   // the newest user
db.identity_users.countDocuments()
```

Useful habits:

- **Newest first.** Most cases look at the record just made: `.sort({createdAt: -1}).limit(1)`. Orders use `placedAt`. The order number shown on screen is stored as `number`: `db.order_orders.findOne({number: "…"})`.
- **Ids.** Records use generated string ids (`_id`). The id of the thing on screen is usually in the browser address bar.
- **Money** is stored in minor units: Rs 450.00 is `45000`. Field names end in `Minor`.
- **Personal data is encrypted.** Emails, phone numbers, rider ID numbers and payout accounts are stored as `…Cipher` fields, never as plain text. A check passes only if you *cannot* read the value. You cannot search by email; find users by `displayName` or by time instead.
- **Passwords** are stored as an Argon2id hash (`passwordHash` starts with `{argon2}$argon2id$`), never as plain text.

### Events (the outbox and Kafka)

Every change that other modules care about writes an event to the module's outbox collection, in the same transaction as the change itself. A relay then sends it to Kafka.

```js
use foodstack_order
db.order_outbox.find().sort({occurredAt: -1}).limit(5)
```

An event record has `type` (for example `foodstack.order.OrderConfirmed.v1`), `subject` (the id it is about), `data` (the event as JSON) and `status`. `status` is `NEW` when written and `SENT` once Kafka has it, normally within a second. An event stuck at `NEW` is a failure.

To watch a Kafka topic live (topics are `foodstack.<module>.events`):

```bash
docker exec -it <kafka container> /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic foodstack.order.events --from-beginning
```

### Redis

```bash
docker exec -it <redis container> redis-cli
KEYS foodstack:*            # fine on a test machine, never on a real one
TTL foodstack:identity:code:LOGIN:<userId>
```

### Elasticsearch

```bash
curl -s -u elastic:foodstack-test "http://localhost:<port>/foodstack_search_stores/_doc/<storeId>?pretty"
```

### Emails

Open the Mailpit address printed at start-up. Every email the run sends is there: sign-in codes, order updates, notices.

### The browser

F12 shows the requests (Network tab) and what the app keeps in the browser (Application tab). Some cases check these, for example that the refresh cookie is `HttpOnly`.
