# 06 Cart and ordering

[Back to QA index](README.md)

The cart, checkout, placing an order, and the store's side: the live board, accepting, rejecting, preparing, ready, collected, cancelling, and the time limit to answer. Card payment is in [07-payments.md](07-payments.md), coupons in [08-coupons.md](08-coupons.md), and riders in [09-delivery-and-riders.md](09-delivery-and-riders.md).

Databases: `foodstack_cart.cart_carts`, `foodstack_order` (`order_orders`, `order_active`, `order_placement_keys`). Events: `order_outbox`, Kafka `foodstack.order.events`.

An order's status moves like this:

```
cash:  PLACED → CONFIRMED → ACCEPTED → (PREPARING) → READY → PICKED_UP → DELIVERED   (delivery)
                                                        READY → COLLECTED                (pickup)
card:  PLACED → PAYMENT_PENDING → CONFIRMED → … the same
ends:  REJECTED (store says no, or no answer in time), CANCELLED, FAILED (card not paid)
```

The back office shows `CONFIRMED` as **New** and `PICKED_UP` as **Out for delivery**. The customer app shows `CONFIRMED` as **Waiting for the restaurant** and `PICKED_UP` as **On the way**.

## Cart

### ORD-01 Add dishes to the cart

Who: `customer@demo.foodstack.local` · Where: customer app, Spice Route Gulberg, location 31.518, 74.352

Steps
1. Click **Chicken tikka**, set quantity 2, the note "Less spicy", and click **Add · Rs …**.
2. Add a dish with required choices without picking one, then pick one.
3. Open the cart (**View order · {n}**). Change a quantity with **One more** and **One less**, then **Remove** a line.
4. Sign out, then try to add a dish.

Expect
- Step 1: the cart shows the line, the note and the subtotal.
- Step 2: the dialog says **Required** and **Add** does nothing until a choice is made.
- Step 4: "Sign in to order".

Check
- `cart_carts`, `_id` = the customer's id: `storeId`, `lines` (each with `lineId`, `itemId`, `quantity`, `optionIds` and `note`), `updatedAt` and `version` going up with each change. No prices are stored in the cart.

### ORD-02 One store at a time

Who: customer · Where: customer app

Steps
1. With dishes from Spice Route in the cart, open Napoli Slice and add a pizza.

Expect
- "Start a new order? Your order has dishes from Spice Route · Gulberg. Starting here removes them." **Start new order** empties the cart and adds the pizza. **Cancel** keeps the old cart.
- The header of other store pages shows "You have an order from {store}" with **Continue**.

Check
- After **Start new order**: `cart_carts.storeId` is Napoli Slice's id and `lines` has only the pizza.

## Checkout

### ORD-03 Place a cash order for delivery

Who: customer · Where: customer app, cart with 2 × Chicken tikka from Spice Route Gulberg

Steps
1. Click **Go to checkout**.
2. **How do you want it?** Delivery. **Deliver to**: the saved location, "House 14, Street 7, Gulberg III", directions "Ring twice". **Phone number** `+92 300 1112233`. **Payment** Cash.
3. Check the **Summary** and click **Place order · Rs …**.

Expect
- The order page opens: **Order {number}**, **Waiting for the restaurant**, a timeline, the items, the total, and **Pay in cash**. A **Live** badge shows that updates arrive by themselves.
- The cart is empty.
- Mailpit: an "Order {number} received" email.

Check
- `order_orders` (find it with `{number: "<number on screen>"}`):
  - `status: "CONFIRMED"`, `fulfilment: "DELIVERY"`, `deliveryMode: "OPERATOR"`, `paymentMethod: "CASH"`, `currency: "PKR"`.
  - `store` holds a copy of the branch (`storeId`, `name: "Spice Route · Gulberg"`, `lat`, `lng`, `ownerUserIds`, `timezone`).
  - `destination`: `lat`, `lng`, `addressLine`, `instructions: "Ring twice"`.
  - `contactPhone` is encrypted (you cannot read the number).
  - `lines[0]`: `quantity: 2`, `unitPriceMinor: 65000`, `lineTotalMinor: 130000`, and the dish name in every language.
  - `totals`: `subtotalMinor`, `deliveryChargeMinor`, `serviceFeeMinor`, `taxMinor`, `tipMinor`, `itemsDiscountMinor`, `deliveryDiscountMinor`, `totalMinor`. They add up as in ORD-04.
  - `commission`: `basisPoints` and `fixedMinor` from the branch.
  - `timeline`: `PLACED` (actor `CUSTOMER`), then `CONFIRMED` (actor `SYSTEM`, note "Cash on delivery").
  - `acceptBy` is `placedAt` plus the store answer time (SET-04).
- `order_active`: a small copy with the same `_id`, `storeId`, `status: "CONFIRMED"`, `acceptBy`.
- `order_placement_keys`: `_id` = `<customerId>:<Idempotency-Key>`, with `orderId` and `fingerprint`.
- `order_outbox`: `OrderPlaced`, then `OrderConfirmed`, both `SENT`.
- `cart_carts`: no document for the customer any more.
- `foodstack_notification.notification_inbox`: `order-confirmed` for the customer and `store-new-order` for the branch's owner.

### ORD-04 The total adds up

Who: customer · Where: checkout, with a service fee, tax and tips on (SET-03), and a coupon (CPN-01)

Steps
1. Add a tip and a coupon, and read the summary.

Expect
- **Total** = **Subtotal** − **Discount** + **Delivery** − delivery discount + **Service fee** + **Tax** + **Tip**.
- The order page and the stored order show exactly the same numbers.

### ORD-05 Checkout problems

Who: customer · Where: checkout

Steps (try each one, then put things back)
1. The branch is closed now (STORE-09 or STORE-10).
2. A dish in the cart is now sold out (STORE-06).
3. The delivery point is outside the branch's area.
4. The subtotal is below the zone's minimum order.
5. Pickup at a branch with pickup off.
6. Cash is turned off (SET-03) and Cash is chosen.

Expect
1. "The restaurant is closed right now."
2. "Some dishes changed or sold out. Check your order."
3. "The restaurant does not deliver to this location."
4. "The minimum order for delivery here is Rs …"
5. "This restaurant does not offer pickup."
6. "Choose another way to pay."
- In every case the place-order button is off and no order is made.

Check
- Network: `POST /api/v1/carts/me/checkout` answers `orderable: false` with the `problems`. `order_orders.countDocuments()` does not change.

### ORD-06 Price changed while checking out

Who: customer, admin · Where: customer app, then back office

Steps
1. Open checkout and leave it.
2. As admin, change the dish price (STORE-05).
3. Click **Place order**.

Expect
- "Prices changed since you looked. Check the new total and place the order again." The summary now shows the new total. Placing again works.

Check
- Network: `POST /api/v1/orders` answers 409 with code `PRICE_CHANGED`.

### ORD-07 Double click and network retry place one order

Who: customer · Where: customer app, then terminal

Steps
1. Double-click **Place order**.
2. In the browser tools, copy the `POST /api/v1/orders` request as curl (with its `Idempotency-Key`) and send it again.
3. Send it once more with the same key but a different tip.

Expect
1. One order.
2. The answer is the same order, not a new one.
3. Refused with 409 `IDEMPOTENCY_KEY_REUSED`: the same key cannot be used for a different order.

Check
- `order_placement_keys` has one document for that key. `order_orders` has one order.

### ORD-08 Pickup order

Who: customer, then napoli · Where: customer app, Napoli Slice, **Pickup**

Steps
1. Order a pizza for **Pickup**, paying cash.
2. As napoli, **Accept** it, then **Mark ready**, then **Collected**.

Expect
- The order page says "Pick up from" with the branch's address, and no delivery charge.
- After **Mark ready**: "Your order is ready to collect." After **Collected**: **Collected** and "Enjoy your meal!"

Check
- `order_orders`: `fulfilment: "PICKUP"`, `totals.deliveryChargeMinor: 0`, final `status: "COLLECTED"`.
- `order_outbox`: `OrderPlaced`, `OrderConfirmed`, `OrderAccepted`, `OrderReady`, `OrderCollected`.
- `foodstack_ledger.ledger_transactions`: `order:<id>:done` appears (FIN-01).

## The store's live board

### ORD-09 New orders arrive by themselves, with sound

Who: `spiceroute@demo.foodstack.local` · Where: back office, **Live orders**

Steps
1. Open **Live orders** and click **Turn on sound**.
2. In another browser, place an order (ORD-03).

Expect
- Within a second or two the order appears under **New**, without reloading, and the board beeps every few seconds until someone acts. A countdown shows the time left to accept.
- Network: an open `GET /api/v1/orders/stores/<storeId>/stream` (server-sent events).

### ORD-10 Accept, prepare, ready

Who: spiceroute · Where: **Live orders**

Steps
1. Click the new order, then **Accept**, set the preparation time to 20 minutes, and confirm.
2. Click **Start preparing**, then **Mark ready**.
3. Watch the customer's order page while you do this.

Expect
- Accepting prints a kitchen ticket (the browser's print dialog), sized for the branch's paper (58 or 80 mm).
- The customer's page moves through **Accepted** ("The kitchen has your order."), **Being prepared** and **Ready**, by itself, with "Expected around {time}".
- The customer gets "{store} accepted your order" in the bell and by email.

Check
- `order_orders`: `status` follows, `prepMinutes: 20`, `readyBy` = accept time + 20 minutes, and each step adds to `timeline` with `actorRole: "STORE"` and `actorId`.
- `order_outbox`: `OrderAccepted`, `OrderPreparing`, `OrderReady`. `OrderAccepted` carries the delivery details for dispatch (DLV-03).
- Redis: messages on `foodstack:order:rt:store:<storeId>` and `foodstack:order:rt:order:<orderId>` (watch with `SUBSCRIBE` in `redis-cli`, or `SSUBSCRIBE` on a cluster).

### ORD-11 Reject an order

Who: spiceroute · Where: **Live orders**

Steps
1. Open a new order, click **Reject**, enter the reason "Out of chicken today", and confirm.

Expect
- "The customer is told at once." The customer's page shows **Declined** with "The restaurant could not take your order. Any card payment is refunded."

Check
- `order_orders`: `status: "REJECTED"`, `closeReason` with the reason, and the timeline entry.
- `order_active`: the order is gone.
- `order_outbox`: `OrderRejected`. A card order is refunded (PAY-05) and a coupon given back (CPN-06).

### ORD-12 No answer in time

Who: customer · Where: customer app (store answer time 60 seconds, SET-04)

Steps
1. Place an order and do not accept it. Wait about 75 seconds.

Expect
- The order turns **Declined** by itself, and the store's board drops it.

Check
- `order_orders`: `status: "REJECTED"`, `closeReason: "NO_ANSWER"`, timeline actor `SYSTEM`.

### ORD-13 Customer cancels before the store accepts

Who: customer · Where: customer app, the order page

Steps
1. Right after placing, click **Cancel order**, then confirm in "Cancel this order?".
2. Place another order, have the store accept it, and look for **Cancel order**.

Expect
1. **Cancelled**: "This order is cancelled. Any card payment is refunded."
2. No cancel button once accepted.

Check
- `order_orders.status: "CANCELLED"`. `order_outbox`: `OrderCancelled`.
- Step 2 through the API: `POST /api/v1/orders/<id>/cancel` answers 409 `ORDER_STATUS_CHANGED`.

### ORD-14 The store or support cancels after accepting

Who: spiceroute, then support · Where: back office

Steps
1. As the store, open an accepted order and click **Cancel order** with a reason.
2. As support, open **Orders**, filter by status, open an order that is being prepared, and cancel it with a reason.

Expect
- "The customer and the store are told." Both orders end **Cancelled**.

Check
- `order_orders.timeline`: the last entry has `actorRole: "STORE"` or `"SUPPORT"` and the reason.

### ORD-15 Order lists and search

Who: support, spiceroute, customer · Where: **Orders** in each app

Steps
1. As support, open **Orders**: every branch's orders. Filter by status, branch and date. Open one to see its **Timeline**.
2. As spiceroute: only Spice Route's branches ("Your branch's orders, newest first.").
3. As the customer: **Your orders** lists only their own, newest first, each opening its page.

Check
- Network: support calls `GET /api/v1/orders?…`, the store `GET /api/v1/orders/stores/<storeId>`, the customer `GET /api/v1/orders/mine`. A customer calling another customer's `GET /api/v1/orders/<id>` gets 404.

### ORD-16 Rate limit on placing orders

Who: customer · Where: terminal

Steps
1. Place more than 10 orders within 10 minutes (the API with new `Idempotency-Key`s is quickest).

Expect
- The 11th is refused: "Too many tries in a short time. Try again in a few minutes." (429, with `Retry-After`).

### ORD-17 Live updates survive a dropped connection

Who: customer and store · Where: both apps

Steps
1. Open the order page and the live board. Turn the network off in F12 (Offline) for 30 seconds, change the order's status from the other side, then go back online.

Expect
- Within a few seconds of going back online, both screens show the current status. Nothing is missed.
