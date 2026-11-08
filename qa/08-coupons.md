# 08 Coupons

[Back to QA index](README.md)

Coupons made by the operator (the platform pays the discount) and by brands and branches (the store pays), and the rules every coupon keeps: dates, minimum, limits, first order only, branches, cities and payment methods. A coupon use is held when the order is placed, counted when it is confirmed, and given back if the order does not go ahead.

Database: `foodstack_promotion`:

| Collection | What it holds |
|---|---|
| `promotion_promotions` | The coupon and its rules, plus `used` |
| `promotion_codes` | `_id` = the code, pointing at the coupon (codes are unique) |
| `promotion_customer_usage` | `_id` = `<couponId>:<customerId>`, with `count` |
| `promotion_customers` | `_id` = customer, with `confirmedOrders` (for first-order coupons) |
| `promotion_redemptions` | `_id` = the order id: one use, `RESERVED`, then `REDEEMED` or `RELEASED` |

### CPN-01 Operator coupon, percent off

Who: admin, then customer · Where: back office **Coupons > New coupon**, then the customer app

Steps
1. Make the coupon `QA10`: **Who offers it** **The platform (you pay the discount)**, **Percent off** 10, **At most** Rs 500, **Uses per customer** 1, **Uses in total** 100, starting now. Save.
2. As the customer, order Rs 650 of food at Spice Route, enter `QA10` under **Coupon code** and click **Apply**.
3. Place the order (cash or card).

Expect
- Step 2: "QA10 applied". The summary shows **Discount** −Rs 65 and the total drops by Rs 65.
- The order page and the store's ticket show the coupon.

Check
- `promotion_promotions`: `owner: "OPERATOR"`, `code: "QA10"`, `type: "PERCENT"`, `percentBasisPoints: 1000`, `maxDiscountMinor: 50000`, `perCustomerLimit: 1`, `totalLimit: 100`, `operatorFundingBasisPoints: 10000`, `active: true`, and `used` goes from 0 to 1.
- `promotion_codes`: `{_id: "QA10", promotionId: …}`.
- `promotion_redemptions`, `_id` = the order id: `status: "RESERVED"` at placing, `"REDEEMED"` once confirmed, with `itemsDiscountMinor: 6500`, `operatorShareMinor: 6500`, `reservedAt` and `redeemedAt`.
- `promotion_customer_usage`: `count: 1` for this coupon and customer.
- `order_orders`: `coupon: {promotionId, code: "QA10", title, operatorShareMinor: 6500}` and `totals.itemsDiscountMinor: 6500`.
- `ledger_transactions`, `order:<id>:done`: a `DISCOUNT` leg of −6500 on `operator:discounts`, and the store's `FOOD` leg is the full Rs 650 (FIN-01).

### CPN-02 Coupon rules refuse

Who: customer · Where: checkout

Steps (one at a time)
1. Enter `NOPE`.
2. Enter `QA10` again after using it once (per-customer limit 1).
3. Make a coupon with **Minimum food total** Rs 2,000 and use it on a Rs 650 order.
4. Make a coupon that ended yesterday and use it.
5. Make a coupon for **Card** only and use it with cash.
6. Make a coupon for one branch and use it at another.
7. Make a coupon with **Only a customer's first order** and use it as `customer@demo.foodstack.local`, who has ordered before.

Expect
1. "No coupon has this code."
2. "This coupon does not apply to this order."
3. "Spend Rs 2,000 to use this coupon."
4. "This coupon has expired."
5–7. "This coupon does not apply to this order."
- In every case the total does not change.

### CPN-03 Free delivery and amount off

Who: admin, customer · Where: **Coupons**, then checkout

Steps
1. Make `QAFREE` (**Free delivery**) and `QA100` (**Amount off** Rs 100).
2. Use each on a delivery order.

Expect
- `QAFREE`: **Delivery** stays on the summary with an equal delivery discount, so the delivery costs Rs 0.
- `QA100`: Rs 100 off the food.

Check
- `order_orders.totals.deliveryDiscountMinor` = the delivery charge for `QAFREE`. `promotion_redemptions.deliveryDiscountMinor` the same.
- The rider's pay is unchanged by a free-delivery coupon (DLV-12).

### CPN-04 City and payment-method limits

Who: admin · Where: **Coupons > New coupon**

Steps
1. Make a coupon for **Cities** Islamabad only, and use it at a Lahore branch.

Expect
- "This coupon does not apply to this order."

Check
- `promotion_promotions.cities: [<Islamabad's id>]`, `paymentMethods` as chosen.

### CPN-05 Brand and branch coupons

Who: spiceroute (brand owner), then napoli (branch manager) · Where: back office, **Coupons**

Steps
1. As spiceroute, **New coupon**: `SPICE15`, 15 % off, for **Branches** Gulberg only. Save. Use it at Gulberg, then at DHA Phase 5.
2. As napoli, with **Branch promotions** off for Napoli Slice (STORE-18): look for **New coupon**.
3. Switch branch promotions on, then as napoli make `NAPOLI50` (Rs 50 off).

Expect
- Step 1: works at Gulberg; refused at DHA Phase 5.
- Step 2: napoli sees **From the brand** coupons and cannot make one.
- Step 3: the coupon is limited to napoli's own branch: "Your own branches. The brand owner makes coupons for the others."
- Brand and branch coupons are paid by the store: the store's share drops by the discount.

Check
- `promotion_promotions`: `owner: "STORE"`, `brandId`, `storeIds`, `operatorFundingBasisPoints: 0`.
- Making a second coupon with an existing code is refused: 409 `COUPON_CODE_TAKEN`.
- napoli making a coupon while branch promotions are off: 403 `BRANCH_PROMOTIONS_OFF`.
- `ledger_transactions`, `order:<id>:done`: the discount is taken from the store's account, not `operator:discounts`.
- Network: a brand owner calling `POST /api/v1/promotions` with another brand's id gets 403.

### CPN-06 A cancelled, rejected or failed order gives the use back

Who: customer · Where: customer app

Steps
1. Make a coupon with **Uses in total** 1. Use it on an order, then cancel the order before the store accepts (ORD-13).
2. Use the same coupon again on a new order.

Expect
- Step 2 works: the cancelled order's use came back.

Check
- After step 1: `promotion_redemptions` for the first order has `status: "RELEASED"` and `releasedAt`. `promotion_promotions.used` went back down. `promotion_customer_usage.count` went back down.
- The same happens for a rejected (ORD-11), timed-out (ORD-12) or failed card order (PAY-02).

### CPN-07 Two customers race for the last use

Who: two customers · Where: two browsers

Steps
1. Make a coupon with **Uses in total** 1.
2. Get both customers to checkout with it, then place both orders at the same moment.

Expect
- One order has the coupon. The other is refused with a message to re-check the order, and goes through without it if placed again.

Check
- `promotion_promotions.used: 1`. Only one `promotion_redemptions` document is `REDEEMED`.

### CPN-08 Switch a coupon off

Who: admin · Where: **Coupons**, the coupon's switch

Steps
1. Click **Switch off QA10**. Try to use it at checkout.
2. Switch it on again.

Expect
- Off: "This coupon does not apply to this order." On: works again (within its limits).

Check
- `promotion_promotions.active: false`, then `true`. `updatedBy` and `updatedAt` change.

### CPN-09 Too many coupon tries

Who: customer · Where: checkout

Steps
1. Try 31 different made-up codes within 10 minutes.

Expect
- After 30 the app refuses further tries for a while ("Too many tries…").
