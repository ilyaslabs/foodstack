# 07 Payments

[Back to QA index](README.md)

Card payments on the gateway's page or inside the app, failed and abandoned payments, refunds, saved cards, and branches that collect card payments into their own merchant account.

The local run uses the **sandbox** gateway: a test page instead of Stripe. No real card is charged. **Card numbers never reach FoodStack's servers or database**; FoodStack keeps only the gateway's references. Several checks below confirm that.

Database: `foodstack_payment` (`payment_intents`, keyed by order id, plus `payment_merchant_accounts` and `payment_store_collection`). Events: `payment_outbox`, Kafka `foodstack.payment.events`.

Before you start: as admin, switch **Card** on in **Settings > Ordering** (SET-03).

### PAY-01 Pay by card on the gateway's page

Who: customer · Where: customer app, **Card style: Hosted page** in **Settings > Ordering**

Steps
1. Place an order with **Card** (the button reads **Continue to payment · Rs …**).
2. On the **Sandbox payment** page (marked TEST MODE), click **Pay Rs …**.

Expect
- Step 1 takes you to the sandbox page.
- Step 2 brings you back to the order page: **Paid by card**, then **Waiting for the restaurant**. The store's board shows the order only after it is paid.

Check
- `order_orders`: the timeline has `PLACED`, then `PAYMENT_PENDING` ("Waiting for the card payment"), then `CONFIRMED` ("Paid by card"). `paidMinor` = `totals.totalMinor`. `paymentUrl` was set.
- `payment_intents`, `_id` = the order id: `status: "SUCCEEDED"`, `amountMinor`, `currency`, `gateway: "sandbox"`, `merchantAccount: "operator"`, `sessionReference` and `paymentReference` (`sbx_…`), `expiresAt` 30 minutes after `createdAt`, `refundedMinor: 0`. **No card number anywhere** in the document.
- `payment_outbox`: `foodstack.payment.PaymentSucceeded.v1`. Then `order_outbox`: `OrderConfirmed`.
- `foodstack_ledger.ledger_transactions`: `payment:<orderId>` moves the amount from `clearing:gateway` to `clearing:prepaid` (FIN-01).

### PAY-02 Card declined

Who: customer · Where: the sandbox page

Steps
1. Place a card order and click **Decline** on the sandbox page.

Expect
- Back on the order page: **Payment failed**, "The payment did not go through, so the order was not sent." The store never sees it.

Check
- `payment_intents.status: "FAILED"` with a `failureReason`. `order_orders.status: "FAILED"`. `order_outbox`: `OrderFailed`. A coupon on it is given back (CPN-06).

### PAY-03 Customer leaves the payment page

Who: customer · Where: customer app

Steps
1. Place a card order, then on the sandbox page press the browser's Back button instead of paying.
2. On the order page, click **Pay now** and pay.

Expect
- Step 1: **Waiting for payment**, "Finish paying to send your order to the restaurant." with **Pay now** and **Cancel order**.
- Step 2 works as PAY-01.

### PAY-04 Payment page runs out

Who: customer · Where: customer app

Steps
1. Place a card order and do nothing for 31 minutes. (To test faster, set the page's `expiresAt` to the past in `payment_intents` and wait one minute.)

Expect
- The order turns **Payment failed**. Opening the old payment link says "This payment is no longer open."

Check
- `payment_intents.status: "EXPIRED"`. `payment_outbox`: `PaymentFailed`, reason `EXPIRED`. `order_orders.status: "FAILED"`.

### PAY-05 Refund when the store rejects or cancels

Who: customer, then spiceroute · Where: both apps

Steps
1. Pay an order by card (PAY-01).
2. As the store, **Reject** it (ORD-11).

Expect
- The customer's page shows **Declined** and "Rs … refunded".

Check
- `payment_intents`: `refundedMinor` = the amount, and `refunds` has one entry (`id` = the rejecting event's id, `amountMinor`, `status: "SUCCEEDED"`, `gatewayReference`).
- `payment_outbox`: `RefundSucceeded`. The refund appears on the order's timeline.
- Repeating the Kafka event never refunds twice: the refund id is the event id.

### PAY-06 Support refunds part of an order

Who: support · Where: back office, **Orders**, a delivered card order

Steps
1. Open the order and click **Refund to the card**.
2. Enter Rs 100, a reason, and under **Who pays it** pick **The store** ("Taken from what we owe the store for this order."). Confirm.
3. Repeat with **We do** ("The store keeps its full share; we bear the refund.").
4. Try to refund more than is left.

Expect
- Each refund shows on the order's timeline. The customer's page shows the amount refunded.
- Step 4 is refused.

Check
- `order_orders`: `refundRequestedMinor` and `refundedMinor` grow. `order_outbox`: `OrderRefunded`.
- `payment_intents.refunds` has an entry per refund.
- `foodstack_ledger.ledger_transactions`: a refund transaction charged to the store's account or to the operator, as chosen (FIN-03).
- Step 4: 400 with code `REFUND_AMOUNT_INVALID`.

### PAY-07 Card fields inside the app

Who: admin, then customer · Where: **Settings > Ordering**, **Card style: Embedded**

Steps
1. Switch the card style to embedded and save.
2. Place a card order. The app opens its own **Pay for your order** page.
3. Enter `4242 4242 4242 4242`, any future expiry and any security code, and click **Pay Rs …**.
4. Place another order and use a card ending in `0002`.

Expect
- The page says "Your card details go straight to the payment provider. FoodStack never sees them." and, in the local run, **Test mode** with "No real card is charged. A card number ending in 0002 is declined."
- Step 3 pays the order. Step 4: "The payment did not go through. Please try again."

Check
- `GET /api/v1/payments/<orderId>/checkout` answers the style, the publishable key and a client secret, and never a card number.
- Search the payment database for the card number: `db.payment_intents.find({$where: "JSON.stringify(this).includes('4242424242424242')"}).count()` is 0. Do the same in `foodstack_order`.

### PAY-08 Saved cards

Who: admin, then customer · Where: **Settings > Ordering** (**Saved cards** on, embedded style), customer app

Steps
1. Pay an order with **Save this card for next time** ticked.
2. Place another card order: the saved card is offered. Pay with it.
3. Open **Account > Saved cards** and **Remove card**.

Expect
- Step 2 needs no card number. Step 3 shows "Visa ending 4242", "Expires …", and after removing: "No saved cards yet. You can save a card when you pay."

Check
- `payment_intents.customerReference` is set on the saved-card orders. `foodstack_payment.payment_customers` holds the customer's gateway reference only. The sandbox keeps its pretend cards in `payment_sandbox_cards`.

### PAY-09 Webhook security

Who: tester · Where: terminal

Steps
1. Send a fake success: `curl -i -X POST http://localhost:8080/api/v1/payment-webhooks/sandbox -H 'Content-Type: application/json' -d '{"orderId":"<a pending order>","status":"SUCCEEDED"}'`

Expect
- Refused with 400 `WEBHOOK_INVALID`. The order stays **Waiting for payment**.

## Branches that collect card payments themselves

### PAY-10 Allow a branch to collect

Who: admin (or finance) · Where: **Settings > Ordering** (direct collection on, a default credit limit), then a branch's **Payments** tab

Steps
1. Switch on direct collection for the platform and save.
2. On Napoli Slice, under **Who collects card payments**, choose **The branch**, set a credit limit of Rs 5,000, give a reason, and save.

Expect
- "Saved. It applies to the next card payment." The tab says "The branch is allowed to collect. Connect a merchant account to start." and "The branch owes the operator its commission and fees, netted in payouts."

Check
- `foodstack_store.store_stores.paymentCollection: "DIRECT"` (it is `"OPERATOR"` by default). `store_outbox`: `StorePaymentCollectionChanged`.
- `foodstack_payment.payment_store_collection`, `_id` = the branch: `mode: "DIRECT"`, `suspended: false`.
- `foodstack_ledger.ledger_credit`, `_id` = the branch: `creditLimitMinor: 500000`, `exceeded: false`.

### PAY-11 Connect the branch's merchant account

Who: napoli (the branch manager) · Where: **My store > Payments > Merchant accounts**

Steps
1. Choose **Sandbox**, enter the secret key `sbx_test_napoli` and any webhook secret, and click **Check and connect**.
2. Pay a card order at Napoli Slice.
3. Repeat step 1 with a key the gateway refuses (the sandbox accepts only keys starting `sbx_`; try `sk_bad`).

Expect
- Step 1: "Connected. The gateway accepted the key." The account shows **In use**.
- Step 2: the money goes to the branch's account. The hosted page is always used for a branch's account.
- Step 3: "Refused by gateway". The previous account stays in use.

Check
- `payment_merchant_accounts`: `scope` (branch or brand), `ownerId` = the branch, `gateway`, `secretKey` encrypted, `keyHint` (last few characters), `status: "ACTIVE"`, `current: true`, `verifiedAt`, `connectedBy`.
- `payment_intents.merchantAccount` names the branch's account for that order. `order_orders` records `collectedBy: "STORE"` in the ledger's `ledger_orders`.

### PAY-12 Over the credit limit

Who: customer, finance · Where: customer app, back office

Steps
1. With Napoli collecting itself and a low credit limit, complete enough card orders that the commission it owes passes the limit.
2. Place one more card order at Napoli.
3. As finance, settle Napoli's balance (FIN-05).

Expect
- After step 1 the **Payments** tab says "Suspended: over the credit limit" and "The operator collects again until the balance is settled."
- Step 2 is paid to the operator's account.
- After step 3 the branch collects again.

Check
- `ledger_credit`: `exceeded: true`, `owedMinor` above the limit. `ledger_outbox`: `StoreCreditLimitExceeded`, later `StoreCreditRestored`.
- `payment_store_collection.suspended: true`, then `false`.
- Step 2: `payment_intents.merchantAccount: "operator"`.
