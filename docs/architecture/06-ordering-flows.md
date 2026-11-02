# 6. Ordering flows

[Back to index](README.md)

How an order moves through `cart`, `order`, `payment`, `promotion` and `notification` after M2, traced from the code. Solid arrows are synchronous calls (through a module's `-api` interface, or HTTP from a browser); dashed arrows are Kafka events or Redis messages.

## 6.1 The order's life

Every step is one conditional update of the order's `status` and `revision`, plus the matching `order_active` change and the step's event, in one MongoDB transaction (`OrderLifecycleService.step`). A step that lost a race is tried again on the fresh order or refused with `ORDER_STATUS_CHANGED`.

```mermaid
stateDiagram-v2
    [*] --> PLACED: customer places (cash)
    [*] --> PAYMENT_PENDING: customer places (card)
    PLACED --> CONFIRMED: same transaction
    PAYMENT_PENDING --> CONFIRMED: PaymentSucceeded
    PAYMENT_PENDING --> FAILED: PaymentFailed or page expired
    PAYMENT_PENDING --> CANCELLED: customer cancels
    CONFIRMED --> ACCEPTED: store accepts (prep minutes)
    CONFIRMED --> REJECTED: store rejects, or no answer in time
    CONFIRMED --> CANCELLED: customer or support cancels
    ACCEPTED --> PREPARING
    ACCEPTED --> READY
    PREPARING --> READY
    READY --> COLLECTED: pickup
    READY --> PICKED_UP: delivery
    PICKED_UP --> DELIVERED
    ACCEPTED --> CANCELLED: store or support
    PREPARING --> CANCELLED: store or support
    READY --> CANCELLED: store or support
    COLLECTED --> [*]
    DELIVERED --> [*]
    REJECTED --> [*]
    CANCELLED --> [*]
    FAILED --> [*]
```

Each step publishes its own event on `foodstack.order.events`: `OrderPlaced`, `OrderConfirmed`, `OrderAccepted`, `OrderPreparing`, `OrderReady`, `OrderPickedUp`, `OrderDelivered`, `OrderCollected`, `OrderRejected`, `OrderCancelled`, `OrderFailed`, and `OrderRefunded` for a refund by support. Every event carries the same order summary (ids, number, status, money, store, customer), so a consumer never has to call back.

## 6.2 Cart and checkout quote

The cart keeps only what the customer picked (item, options, quantity, note). Prices are never stored: every read asks the store module for the current menu, so a price change or a sold-out dish shows at once.

```mermaid
sequenceDiagram
    autonumber
    participant B as Customer web app
    participant C as cart-web / CartService
    participant DB as foodstack_cart (cart_carts)
    participant S as StoreApi
    participant G as GeoApi
    participant P as PromotionApi
    participant SE as SettingsApi

    B->>C: POST /api/v1/carts/me/lines (store, item, options, quantity)
    C->>S: validateOrder(store, lines)
    S-->>C: priced lines, problems, store snapshot (menu from Redis cache)
    C->>DB: save lines (version + 1), another store's cart only with replaceOtherStore
    C-->>B: cart with current prices and problems
    B->>C: POST /api/v1/carts/me/checkout (fulfilment, location, coupon, tip, payment)
    C->>S: validateOrder(store, lines)
    C->>G: quote(store, destination) when delivering
    C->>P: evaluate(coupon) when a code is given (30 checks per 10 min per customer, Redis)
    C->>SE: getOrdering() (fees, tax, tips, cash and card switches)
    C-->>B: CheckoutView: totals, problems, orderable
```

Total = subtotal − food discount + delivery − delivery discount + service fee + tax + tip (`CheckoutMath`). The same `CheckoutService` runs again when the order is placed, so the customer pays exactly what they saw.

## 6.3 Place an order (cash)

```mermaid
sequenceDiagram
    autonumber
    participant B as Customer web app
    participant O as order-web / OrderPlacementService
    participant C as CartApi
    participant P as PromotionApi
    participant DB as foodstack_order
    participant R as Redis
    participant K as Kafka

    B->>O: POST /api/v1/orders + Idempotency-Key (expected total)
    O->>DB: order_placement_keys: same key and body seen before? return that order
    O->>R: rate limit (order:place per customer)
    O->>C: checkout(...) (6.2 again)
    alt problems, or total differs
        O-->>B: 409 CHECKOUT_PROBLEMS or PRICE_CHANGED (app re-quotes)
    end
    O->>P: reserve(orderId, coupon) when a coupon applies (conditional counters)
    rect rgba(127,127,127,0.12)
    note over O,DB: one transaction
    O->>DB: insert placement key, order (CONFIRMED, acceptBy), order_active
    O->>DB: outbox: OrderPlaced, OrderConfirmed
    end
    O->>R: PUBLISH order:{id} and store:{storeId}
    O->>C: clear(customer, cartVersion) (only if the cart did not change since)
    O-->>B: 201 {orderId, number, status}
    DB-->>K: relay sends the events
```

If the transaction fails, the held coupon use is released. A second request with the same key and body returns the first order; the same key with a different body is refused.

## 6.4 Pay by card (hosted page)

The card number never reaches FoodStack: the customer pays on the gateway's page (Stripe Checkout, or the built-in sandbox page for tests), and the result comes back by webhook.

```mermaid
sequenceDiagram
    autonumber
    participant B as Customer browser
    participant O as OrderPlacementService
    participant PA as PaymentService
    participant GW as Card gateway (Stripe Checkout or sandbox)
    participant PDB as foodstack_payment (payment_intents)
    participant K as Kafka
    participant OL as order PaymentEventsListener

    B->>O: POST /api/v1/orders (paymentMethod CARD)
    O->>O: transaction: order PAYMENT_PENDING + OrderPlaced
    O->>PA: startCard(orderId, amount, return and cancel URLs)
    PA->>PDB: insert payment (_id = orderId, CREATED)
    PA->>GW: create hosted session (expires in 30 min)
    PA->>PDB: PENDING + session id + redirect URL
    O-->>B: 201 {paymentUrl}
    B->>GW: pays on the gateway's page
    GW->>PA: POST /api/v1/payment-webhooks/{gateway} (signed)
    PA->>PA: check signature, find payment by session
    PA->>PDB: transaction: PENDING to SUCCEEDED + outbox PaymentSucceeded
    GW-->>B: redirect to <web-url>/orders/{id}
    PDB-->>K: relay
    K-->>OL: PaymentSucceeded (once, through the inbox)
    OL->>OL: order PAYMENT_PENDING to CONFIRMED + OrderConfirmed, then empty the cart
```

- A webhook delivered twice, or racing the expiry sweep, changes the payment once (conditional update on the status).
- `payment-expiry` runs every minute on every replica and expires pages past their time: `PaymentFailed` (reason `EXPIRED`) fails the order and gives the coupon back.
- A payment that succeeds after its page expired still counts: the order is already failed, so it is refunded at once.
- If the gateway does not answer when the page is opened, the order fails at once and the customer is told to try again or pay cash.

## 6.5 The store's live board: accept, ready, collected

```mermaid
sequenceDiagram
    autonumber
    participant BO as Back office (Live orders)
    participant OW as order-web
    participant L as OrderLifecycleService
    participant DB as foodstack_order
    participant R as Redis pub/sub
    participant B as Customer order page

    BO->>OW: GET /api/v1/orders/stores/{storeId}/board (order_active)
    BO->>OW: GET /api/v1/orders/stores/{storeId}/stream (SSE)
    OW->>R: SUBSCRIBE <ns>:order:rt:store:{storeId}
    B->>OW: GET /api/v1/orders/{id}/stream (SSE)
    OW->>R: SUBSCRIBE <ns>:order:rt:order:{id}
    BO->>OW: POST /orders/{id}/actions/ACCEPT {prepMinutes}
    OW->>L: accept (store access checked)
    L->>DB: transaction: CONFIRMED to ACCEPTED, readyBy, order_active, OrderAccepted
    L->>R: PUBLISH {orderId, storeId, status, revision} on both channels
    R-->>OW: message on every replica that has a subscriber
    OW-->>BO: event "order"
    OW-->>B: event "order"
    BO->>OW: reload the order (the message carries no personal data)
    B->>OW: reload the order
    BO->>OW: actions READY, then COLLECTED (same path)
```

The board beeps every 4 seconds while an order waits (when sound is on), counts down to the accept deadline and prints a kitchen ticket on accept. Both streams reconnect with a growing pause and reload once on reconnect, so a missed message is never a stale screen.

## 6.6 No answer, cancel, reject, refund

```mermaid
sequenceDiagram
    autonumber
    participant SW as order-accept-timeout (every 15 s, every replica)
    participant L as OrderLifecycleService
    participant K as Kafka foodstack.order.events
    participant PA as payment OrderEventsListener
    participant PR as promotion OrderEventsListener
    participant N as notification OrderNotificationsListener
    participant OL as order PaymentEventsListener

    SW->>L: CONFIRMED orders in order_active with acceptBy in the past
    L->>L: CONFIRMED to REJECTED (reason NO_ANSWER) + OrderRejected {refundMinor}
    L-->>K: OrderRejected (also OrderCancelled from customer, store or support)
    K-->>PA: refund(orderId, refundMinor, refundId = event id) when paid by card
    PA-->>K: RefundSucceeded or RefundFailed (foodstack.payment.events)
    K-->>OL: refund goes on the order's timeline
    K-->>PR: give the coupon use back (inbox: once)
    K-->>N: tell the customer (and the store)
```

- Customers can cancel only before the store accepts. Stores can cancel after accepting; support can cancel at any step before the end and can refund part or all of a paid order (`POST /orders/{id}/refund`, `OrderRefunded`).
- The refund id is the event id, so a redelivered event never refunds twice. If the gateway says "maybe later", the handler throws and the event is retried, then dead-lettered.

## 6.7 Coupons: hold, redeem, give back

```mermaid
flowchart LR
    Q["checkout quote<br/>evaluate (read only)"] --> H["placement<br/>reserve: conditional counters<br/>(total, per customer)"]
    H -->|"OrderConfirmed"| RD["redeem<br/>+ count the customer's orders<br/>(first-order coupons)"]
    H -->|"OrderCancelled, OrderRejected, OrderFailed"| RL["release<br/>counters back"]
    H -->|"placement failed"| RL
```

Counters live in `promotion_codes` (uses in total), `promotion_customer_usage` (`<promotionId>:<customerId>`) and `promotion_customers` (confirmed orders per customer). The reservation is a `promotion_redemptions` document keyed by the order id, so a hold, redeem or release happens once per order. Event handlers go through the inbox because counters are not idempotent.

## 6.8 Notifications: in-app and email

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka (identity, order, payment events)
    participant L as notification listeners
    participant NS as NotificationService
    participant ID as IdentityApi / OrderApi / SettingsApi
    participant DB as foodstack_notification
    participant SW as notification-email (every 10 s)
    participant SMTP as SMTP server

    K-->>L: UserRegistered, EmailCodeIssued, OrderConfirmed, OrderAccepted, OrderReady, ... RefundSucceeded
    L->>ID: who to tell, their language, the order, branding
    L->>NS: message (customer, and store owners for a new order)
    NS->>DB: notification_inbox (id from the event: sent once)
    NS->>DB: notification_preferences allow email? then notification_deliveries QUEUED
    NS->>SMTP: send now
    alt SMTP fails
        NS->>DB: retry in 1, 5, 30, 120 min, FAILED after 5 tries
        SW->>DB: due deliveries
        SW->>SMTP: send again
    end
```

- The bell in the customer app reads `GET /api/v1/notifications` and the unread count; operators see the email log at `GET /api/v1/notifications/deliveries` and can send one again.
- Sign-in and reset codes are "sealed": the email body is stored encrypted and wiped once sent or failed, and the log shows only the subject.
- Order and account emails respect `notification_preferences` (order emails on by default, offers off); sign-in codes are always sent.

## 6.9 Sign in with an email code, reset a password

```mermaid
sequenceDiagram
    autonumber
    participant U as Browser
    participant W as identity-web EmailCodePagesController
    participant CS as EmailCodeService
    participant R as Redis
    participant DB as foodstack_identity
    participant K as Kafka
    participant N as notification

    U->>W: POST /login/code (email)
    W->>CS: issue(LOGIN, email)
    CS->>R: rate limit: 5 codes per email per 15 min
    CS->>DB: find user by email blind index (unknown or blocked: say the same, send nothing)
    CS->>R: HSET <ns>:identity:code:LOGIN:{userId} hash(userId:code) and tries, 5 min TTL
    CS->>DB: outbox EmailCodeIssued (code encrypted)
    DB-->>K: relay
    K-->>N: sealed email with the 6-digit code
    U->>W: POST /login/code/verify (code)
    W->>CS: verify: at most 5 tries, then DEL (single use)
    W->>W: sign in (factor OTT), new session id, continue to the app
```

Password reset is the same with purpose `PASSWORD_RESET`: the new password is checked before the code is used up, then the password is changed and every token of the user is revoked (`PASSWORD_RESET`). A signed-in user changes their password with `PUT /api/v1/me/password` (current password required).
