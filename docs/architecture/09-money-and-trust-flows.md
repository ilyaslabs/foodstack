# 9. Money, reviews and privacy flows (M4)

[Back to index](README.md)

What M4 added, traced from the code: the `ledger` and `review` modules, stores that collect card payments themselves, card fields inside the app and saved cards, a customer's data export and account deletion, and the operator's message editor. Solid arrows are synchronous calls (HTTP from a browser, or a module's `-api` interface); dashed arrows are Kafka events.

## 9.1 Ledger: posting an order

`ledger` never asks other modules for anything. It listens to `order`, `payment`, `dispatch`, `store` and `settings` events and writes balanced double-entry transactions into `ledger_transactions` (append-only, sharded by `storeId`). Each transaction has a fixed id (`order:<id>:done`, the event id for refunds), so a redelivered event posts nothing new. The rules are pure functions in `Postings`, each tested on its own.

```mermaid
sequenceDiagram
    autonumber
    participant O as order
    participant K as Kafka
    participant L as ledger (OrderEventsListener)
    participant P as LedgerPosting
    participant M as MongoDB ledger
    participant C as CreditLimits
    O-->>K: OrderPlaced
    K-->>L: OrderPlaced
    L->>P: orderPlaced (remember the branch)
    O-->>K: OrderDelivered or OrderCollected (with OrderMoney)
    K-->>L: delivered or collected
    L->>P: orderDone(order)
    P->>P: Postings.orderCompleted (legs sum to zero)
    P->>M: insert transaction order:ID:done (duplicate key = already posted)
    P->>M: post refunds that waited for the order
    L->>C: check(storeId)
    C->>M: branch balance from daily roll-ups plus recent lines
    alt a direct-collecting branch crosses its credit limit
        C-->>K: StoreCreditLimitExceeded or StoreCreditRestored
    end
```

Where the customer's money comes from in `orderCompleted`:

| Order | Taken from |
|---|---|
| Card, operator's account | `prepaid` clearing account (filled by `PaymentSucceeded`) |
| Cash, operator rider | `cash` clearing account (the rider's cash, moved onto the rider by `DeliveryCompleted`) |
| Cash, store delivers or pickup | the branch account, line kind `CASH` (the branch holds it for the operator) |
| Card into the branch's own merchant account | the branch account, line kind `COLLECTED` |

It is then shared out: food, tax and the store's own delivery charge to the branch, less the discounts it funds and the commission. Commission (percent of food after store-funded discounts, plus a fixed amount), the operator's delivery charge and the service fee go to the operator, less the discounts it funds. Tips go to the branch, or to the rider pool for operator deliveries until `DeliveryCompleted` names the rider.

A refund on an order still in progress waits on the order's row (`pendingRefunds`) and is posted when the order is done, against whoever support chose (`fundedBy`, default store). If the order is cancelled instead, the waiting refund is dropped, because the customer simply gets the money back.

## 9.2 Ledger: roll-ups, payouts and settlement

```mermaid
sequenceDiagram
    autonumber
    actor F as Finance (back office)
    participant W as ledger-web
    participant R as LedgerReports
    participant U as Rollups (PartitionedTask)
    participant M as MongoDB ledger
    participant K as Kafka
    loop every interval, each replica over its leased buckets
        U->>M: fold lines not rolled up yet into ledger_daily (account, day, kind, bucket)
    end
    F->>W: GET /api/v1/ledger/payouts?party=STORE&from&to
    W->>R: payouts
    R->>M: ledger_daily rows plus lines not rolled up yet
    R-->>F: one row per party, netted (OPERATOR_PAYS or PARTY_PAYS)
    F->>W: GET /payouts.csv or /payouts.pdf
    F->>W: POST /api/v1/ledger/settlements (amount, reference)
    W->>M: SETTLEMENT transaction plus settlement record
    W-->>K: PayoutSettled or StoreBalanceSettled
```

A store owner calling the same endpoints sees only their own branches. Days are counted in the deployment's time zone (`settings` locale).

## 9.3 Stores that collect card payments themselves

```mermaid
sequenceDiagram
    autonumber
    actor A as Finance
    actor S as Store owner
    participant ST as store
    participant K as Kafka
    participant PY as payment (MerchantAccounts)
    participant G as Card gateway
    participant OR as order
    participant L as ledger
    A->>ST: allow direct collection for the branch (credit limit)
    ST-->>K: StorePaymentCollectionChanged
    K-->>PY: collectionChanged
    K-->>L: CreditLimits.collectionChanged
    S->>PY: PUT /api/v1/payments/merchant-accounts/BRANCH/id (keys)
    PY->>G: harmless test call with the keys
    PY->>PY: only now replace the previous account
    Note over OR,PY: Checkout
    OR->>PY: resolve(storeId)
    PY-->>OR: branch account, else brand account, else operator
    OR->>OR: order money records collectedBy STORE or OPERATOR
    L-->>K: StoreCreditLimitExceeded when the branch owes too much
    K-->>PY: suspended, new card orders go to the operator again
```

A branch's account is used only while the deployment switch is on, finance allows the branch, and the ledger has not suspended it. If the gateway refuses the keys while taking a payment (`markFailed`), new orders go to the operator too. Webhooks for a store account arrive at `/api/v1/payment-webhooks/{gateway}/{scope}/{ownerId}`, checked with that account's secret.

## 9.4 Card fields inside the app and saved cards

Card numbers never reach FoodStack's servers or page code. The gateway's script draws its own fields inside its own frame (Stripe embedded Checkout); on the hosted style the customer leaves for the gateway's page. FoodStack keeps only the gateway's customer reference.

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant WEB as web /pay/orderId
    participant PW as payment-web
    participant PS as PaymentService
    participant G as Card gateway
    participant K as Kafka
    C->>WEB: place a card order (paymentUrl is the app's /pay page)
    WEB->>PW: GET /api/v1/payments/orderId/checkout
    PW->>PS: checkout(orderId), own order only
    PS->>G: create session (embedded, customer reference if saving is on)
    G-->>PS: client secret
    PS-->>WEB: style EMBEDDED, publishable key, client secret
    WEB->>G: load the gateway script, initEmbeddedCheckout
    C->>G: types the card in the gateway's frame (or picks a saved card)
    G-->>PW: webhook payment succeeded
    PW-->>K: PaymentSucceeded
    C->>PW: GET /api/v1/payments/cards (account page)
    PW->>G: list the customer's cards (brand, last 4, expiry)
    C->>PW: DELETE /api/v1/payments/cards/cardId
    PW->>G: forget the card
```

The operator picks the style (`cardStyle`: `HOSTED_PAGE` or `EMBEDDED`) and whether customers may save cards (`savedCardsEnabled`) on the back-office Ordering page. Both apply to the operator's account only; a branch's own account always uses the hosted page. The sandbox gateway imitates the embedded fields with a pretend form posting to `/api/v1/payments/sandbox/{orderId}/card`.

## 9.5 Reviews

`review` learns which orders can be rated, and by whose rider, from `order` and `dispatch` events (`FinishedOrdersListener` sets fields on `review_orders`, so arrival order does not matter).

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    actor S as Store owner
    actor A as Moderator
    participant RW as review-web
    participant RS as ReviewService
    participant B as BlockedWords
    participant M as MongoDB review
    participant K as Kafka
    C->>RW: POST /api/v1/reviews (stars, text, rider stars and tags)
    RW->>RS: submit
    RS->>M: order is done and inside the rating window, not rated yet
    RS->>B: mask the text
    B-->>RS: masked text, flagged if a word was blocked
    RS->>M: review, store and rider stats in one transaction
    RS-->>K: ReviewPublished
    S->>RW: POST /api/v1/reviews/id/reply (once)
    A->>RW: GET /api/v1/reviews/moderation?filter=FLAGGED
    A->>RW: POST /id/hide, /id/show or /id/keep
    RS-->>K: ReviewHidden (stats recounted)
```

The public store page shows the customer as first name and last initial. A second rating of the same order gets `409 REVIEW_ALREADY_RATED`.

## 9.6 Data export and account deletion

The export is composed by the customer's browser from each module's own endpoint, so no module reads another's data: `GET /api/v1/me/export` (identity), `/api/v1/orders/mine/export`, `/api/v1/reviews/mine/export` and `/api/v1/notifications/mine/export`.

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant ID as identity (AccountDeletion)
    participant K as Kafka
    participant N as notification
    participant O as order
    participant D as dispatch
    participant R as review
    participant P as payment
    participant G as Card gateway
    C->>ID: POST /api/v1/me/deletion
    ID->>ID: customer only, set deleteAfter (grace, 14 days default)
    ID-->>K: AccountDeletionScheduled
    ID->>ID: revoke every token (signed out everywhere)
    K-->>N: email the deletion notice
    Note over ID: signing in before deleteAfter cancels it, after it is refused
    loop sweep every 30 minutes (foodstack.identity.deletion-sweep)
        ID->>ID: runDue: conditional update keeps only the id
        ID->>ID: remove login key, addresses, refresh tokens
        ID-->>K: UserDeleted
    end
    K-->>O: CustomerErasure, orders become Deleted user, point rounded
    K-->>D: CustomerErasure, delivery jobs anonymised
    K-->>R: reviews stay under Deleted user, photos released
    K-->>N: inbox, devices and preferences removed
    K-->>P: eraseCustomer
    P->>G: forget the customer and saved cards
```

Accounts with back-office roles get `409 ACCOUNT_HAS_STAFF_ROLES`: an admin closes them. Every consumer is safe to run again.

## 9.7 The operator's message editor

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    participant NW as notification-web (TemplatesController)
    participant E as TemplateEditor
    participant T as Templates
    participant M as MongoDB notification_templates
    participant N as notification senders
    A->>NW: GET /api/v1/notifications/templates/id
    NW->>E: built-in text and own wording per enabled language
    A->>NW: POST .../id/language/preview (sample values, email layout)
    A->>NW: PUT .../id/language (If-Match version)
    E->>E: only the message's placeholders, code messages keep the code
    E->>M: insert, or replace where version matches
    A->>NW: DELETE .../id/language (back to built-in)
    N->>T: render(template, language, values)
    T->>M: the operator's wording, field by field over the built-in text
```

A language without built-in text uses the English wording, the operator's English wording included.
