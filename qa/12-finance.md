# 12 Finance

[Back to QA index](README.md)

The ledger that records who owes whom for every order, the payout list for finance and for store owners, CSV and PDF exports, statements, settling a balance, and the credit limit of branches that collect card payments themselves.

Database: `foodstack_ledger`:

| Collection | What it holds |
|---|---|
| `ledger_transactions` | One entry per money event, never changed afterwards. `_id` says what caused it (table below), with `type`, `storeId`, `brandId`, `orderId`, `legs` (each `{accountId, kind, amountMinor}`), `currency`, `day` (`yyyy-MM-dd`), `at`, `note`, `rolled` |
| `ledger_daily` | Daily roll-ups the reports read. `_id` = `<account>\|<day>\|<kind>\|<bucket>`, with `amountMinor` and `lines`. Built every 30 seconds |
| `ledger_accounts` | One per branch or rider seen: `partyType`, `partyId`, `name`, `brandId`, `currency` |
| `ledger_orders` | `_id` = the order: `done`, `closed`, `collectedBy` (`OPERATOR` or `STORE`), `pendingRefunds` |
| `ledger_settlements` | One per settled balance: `accountId`, `periodTo`, `amountMinor`, `reference`, `settledBy`, `settledAt` |
| `ledger_credit` | `_id` = a branch that collects itself: `mode`, `creditLimitMinor`, `exceeded`, `owedMinor`, `checkedAt` |

| Transaction `_id` | `type` | When |
|---|---|---|
| `payment:<orderId>` | `PAYMENT` | Card money reached the operator's gateway |
| `order:<orderId>:done` | `ORDER_DONE` | The order was delivered or collected |
| `delivery:<orderId>` | `DELIVERY` | An operator rider finished the delivery |
| `refund:<refundId>` | `REFUND` | Support refunded part of a finished order |
| `refund-paid:<refundId>` | `REFUND_PAID` | Card money went back to the customer |
| `settlement:<settlementId>` | `SETTLEMENT` | Finance settled a balance |

Accounts: `store:<branchId>` and `rider:<riderId>` (a positive balance is what the operator owes them), the operator's own `operator:commission`, `operator:delivery`, `operator:service-fee`, `operator:discounts`, `operator:rider-pay`, `operator:refunds`, `operator:bank`, and money on its way: `clearing:gateway`, `clearing:prepaid`, `clearing:cash`, `clearing:rider-pool`.

**The legs of every transaction add up to zero.** A quick check after any case:

```js
use foodstack_ledger
db.ledger_transactions.aggregate([{$project: {s: {$sum: "$legs.amountMinor"}}}, {$match: {s: {$ne: 0}}}]).toArray()   // must be []
```

### FIN-01 A finished order is shared out

Who: tester · Where: mongosh, after orders from ORD-03 (cash, operator rider), ORD-08 with a card (pickup) and CPN-01 (operator coupon)

Steps
1. Find the order ids in `foodstack_order.order_orders`.
2. Read each order's ledger transactions: `db.ledger_transactions.find({orderId: "<id>"})`.

Expect, for a Rs 1,300 cash delivery at Spice Route Gulberg (commission 15 %, delivered by an operator rider, delivery Rs 189.39):

| Transaction | Account | Kind | Amount |
|---|---|---|---|
| `order:<id>:done` | `clearing:cash` | `PAYMENT` | −148939 |
| | `store:<Gulberg>` | `FOOD` | +130000 |
| | `store:<Gulberg>` | `COMMISSION` | −19500 |
| | `operator:commission` | `COMMISSION` | +19500 |
| | `operator:delivery` | `DELIVERY` | +18939 |
| `delivery:<id>` | `rider:<rider>` | `EARNING` | + the rider's pay |
| | `operator:rider-pay` | `EARNING` | − the rider's pay |
| | `rider:<rider>` | `CASH` | −148939 (the cash the rider carries) |
| | `clearing:cash` | `PAYMENT` | +148939 |

For a card order there is also `payment:<id>` from `clearing:gateway` to `clearing:prepaid`, and the done order takes the money from `clearing:prepaid`. For an operator coupon of Rs 65 the store's `FOOD` stays the full Rs 650 and `operator:discounts` gets `DISCOUNT` −6500 (CPN-01).

Also check
- Commission is the percentage of the food after the discounts the **store** funds, plus the fixed amount per order if set (STORE-16). A store coupon of Rs 50 on Rs 650 at 15 %: `store:` `DISCOUNT` −5000 and `COMMISSION` −9000.
- Tax goes to the store as `TAX`. A service fee goes to `operator:service-fee`.
- A store that delivers itself gets the delivery charge (`store:` `DELIVERY`), and cash goes to the store as `store:` `CASH` instead of `clearing:cash`.
- A tip on an operator delivery goes to `clearing:rider-pool`, then to the rider (`rider:` `TIP`) in `delivery:<id>`. On pickup or a store's own rider it goes to the store.
- A store's own rider gets no ledger entries: the store pays them.
- `ledger_orders.<id>`: `done: true`, `collectedBy: "OPERATOR"`.
- Sending the same order event again changes nothing: the `_id` is fixed per order.

### FIN-02 Orders that do not go ahead

Who: tester · Where: mongosh

Steps
1. Reject a cash order (ORD-11) and a card order (PAY-05).

Expect
- The cash order has no ledger transaction at all. `ledger_orders`: `closed: true`.
- The card order has `payment:<id>` and `refund-paid:<refundId>` that moves the money back from `clearing:prepaid` to `clearing:gateway`. No `order:<id>:done`. The store's balance does not change.

### FIN-03 Refunds and who pays for them

Who: support, then tester · Where: back office (PAY-06), then mongosh

Steps
1. Refund Rs 100 of a delivered card order with **Who pays it: The store**.
2. Refund Rs 100 with **We do**.

Expect
1. `refund:<refundId>`: `store:<branch>` `REFUND` −10000 and `clearing:prepaid` `PAYMENT` +10000. Then `refund-paid:<refundId>` when the gateway confirms.
2. The same, but the −10000 is on `operator:refunds`, so the store's balance does not change.
- For a branch that collects itself, the money leaves the branch's own account: `store:` `COLLECTED` +10000 instead of `clearing:prepaid`.

### FIN-04 The payout list

Who: finance (`finance@demo.foodstack.local`) · Where: back office, **Finance**

Steps
1. Open **Finance**. Try **This week**, **Last week**, **This month**, **Last month** and a date range with **From** and **To**.
2. Switch between **Branches** and **Riders**. With branches, pick one brand under **Brand**.
3. Click **Statement** on Spice Route · Gulberg. Click **Show more** if it is there.
4. Click **CSV** and **PDF**.

Expect
- The page says "What the operator owes each branch and rider, and what they owe it, netted for the period."
- Tiles: **We pay out** (and how many branches), **We receive**, and **Operator earnings** with the commission, delivery charges and service fees less discounts, rider pay and refunds borne.
- Branch rows: **Opening**, **Sales**, **Deductions**, **Refunds**, **Cash held**, **Settled**, **Net** with **We pay**, **They pay** or **Settled**. Rider rows: **Delivery pay**, **Tips**, **Cash carried**, **Settled**.
- A rider who carried cash for a cash order and was paid less than that shows **They pay**: the rider owes the operator the cash.
- The statement shows the period and "balance now", then every line of the period: what it was (**Order**, **Delivery**, **Refund**, **Card payment**, **Settlement**), its amount, its time and note (such as the gateway or bank reference), and the kinds it is made of (Food, Commission and so on). An empty period says "Nothing in this period."
- The CSV opens in a spreadsheet as numbers in rupees (not minor units), with the columns `Branch`, `Branch id`, `Brand id`, `Opening balance`, one column per kind, `Closing balance`, `Net to settle`, `Who pays`, `Currency`, `From`, `To`, and a `Total` row. The PDF shows the same, with the operator's name.

Check
- The numbers match the sum of `ledger_daily.amountMinor` for that account and those days, and also the sum of the legs in `ledger_transactions` (roll-ups run every 30 seconds, so wait half a minute after an order).
- Network: `GET /api/v1/ledger/payouts?party=STORE&from=…&to=…`, `/payouts.csv`, `/payouts.pdf`, `/accounts/store:<id>/statement`, `/operator`.

### FIN-05 Settle a balance

Who: finance · Where: back office, **Finance**

A balance can be settled only up to yesterday. To test on the same day as the orders, move them to yesterday first:

```js
use foodstack_ledger
const y = new Date(Date.now() - 86400000).toISOString().slice(0, 10)
db.ledger_transactions.updateMany({}, {$set: {day: y, rolled: false}})
db.ledger_daily.deleteMany({})   // rebuilt within 30 seconds
```

Steps
1. Choose a period that ends yesterday. On Spice Route · Gulberg, click **Settle**.
2. Leave **Bank or payment reference** empty, then enter `BANK-TRX-0001` and confirm.
3. Try to settle the same period again, and a period that ends today.
4. Settle a rider who owes cash.

Expect
- The dialog says "You paid Spice Route · Gulberg Rs … for everything up to …" (or "… paid you Rs …"). The reference is required.
- Step 2: "Spice Route · Gulberg is settled up to …". The row shows **Settled** and the statement shows the settlement with the reference.
- Step 3: "Nothing is due for this period", and "Settle up to yesterday at the latest; today is not over yet". There is no **Settle** button on a settled row.
- Step 4: "… paid you Rs …".

Check
- `ledger_settlements`: `accountId: "store:<Gulberg>"`, `periodTo`, `amountMinor` (positive when the operator paid, negative when the party paid), `reference: "BANK-TRX-0001"`, `settledBy`, `settledAt`.
- `ledger_transactions`: `settlement:<id>` with `store:<Gulberg>` `SETTLEMENT` −due and `operator:bank` `SETTLEMENT` +due.
- `ledger_outbox`: `PayoutSettled`, and for a branch `StoreBalanceSettled`.
- API errors: `SETTLEMENT_REFERENCE_REQUIRED` (400), `SETTLEMENT_PERIOD_OPEN` (400), `NOTHING_TO_SETTLE` (409), `SETTLEMENT_PERIOD_SETTLED` (409, for a period before one already settled).

### FIN-06 Who sees which balances

Who: spiceroute, napoli, support, finance · Where: back office

Steps
1. As spiceroute, open **Payouts**.
2. As napoli (a branch manager), open **Payouts**.
3. As spiceroute, call `GET /api/v1/ledger/payouts?party=RIDER&from=…&to=…`, and the statement of `store:<Napoli Slice's id>`.
4. As support, open `/admin/finance`.
5. As spiceroute, call `POST /api/v1/ledger/settlements`.

Expect
1. "What the operator owes your branches, and what they owe it, netted for the period." Only Spice Route's branches, with **Due to you** and **You owe**. No **Settle** button, no riders, no operator earnings.
2. Only napoli's own branch.
3. 403 `LEDGER_ACCESS_DENIED` ("Only finance sees rider balances", "You cannot see this account").
4. No **Finance** in the menu. Typing the address opens an empty page with "The balances could not be loaded.": the server answers 403 `LEDGER_ACCESS_DENIED`.
5. 403 `LEDGER_ACCESS_DENIED`: "Only finance can settle balances".

### FIN-07 Credit limit of a branch that collects itself

Who: finance, customer · Where: back office, then customer app (PAY-10 to PAY-12)

Steps
1. Let Napoli Slice collect with a credit limit of Rs 500 (PAY-10, PAY-11).
2. Complete card orders at Napoli until the commission it owes passes Rs 500.
3. Settle Napoli's balance (FIN-05).

Expect
- Step 2: the ledger shows `store:<Napoli>` `COLLECTED` −total for each order, so Napoli's balance goes negative (it owes the operator). Once below −Rs 500, Napoli is suspended (PAY-12) and finance gets the "over its credit limit" email (NTF-08).
- Step 3: the branch collects again.

Check
- `ledger_orders.collectedBy: "STORE"` for the direct orders.
- `ledger_credit.<Napoli>`: `mode: "DIRECT"`, `creditLimitMinor: 50000`, `exceeded: true`, `owedMinor` above 50000, then `exceeded: false` after settling.
- `ledger_outbox`: `StoreCreditLimitExceeded`, then `StoreCreditRestored`.
- A branch the operator collects for is never suspended, whatever it owes.
