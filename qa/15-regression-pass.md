# 15 Regression pass

[Back to QA index](README.md)

A short pass over the cases that matter most, for every release candidate and after any risky change. It takes about an hour on the local run. Do the **Check** parts too: they catch what the screen hides. If anything fails, run that whole file.

Start the local run fresh (README), so the demo data is the same as everyone else's.

| # | Case | What it proves |
|---|---|---|
| 1 | [AUTH-01](01-authentication.md#auth-01-create-a-customer-account) | A customer can sign up; the password is hashed and the email encrypted |
| 2 | [AUTH-03](01-authentication.md#auth-03-sign-in-with-email-and-password), [AUTH-08](01-authentication.md#auth-08-sign-in-with-an-emailed-code) | Password and emailed-code sign-in |
| 3 | [AUTH-13](01-authentication.md#auth-13-the-session-survives-a-reload-and-renews-itself), [AUTH-16](01-authentication.md#auth-16-sign-out-everywhere) | Sessions renew, and signing out everywhere works at once |
| 4 | [AUTH-20](01-authentication.md#auth-20-sign-in-with-the-authenticator-code) | Two-step sign-in for the back office |
| 5 | [USER-05](02-users-and-roles.md#user-05-side-menu-per-role), [USER-06](02-users-and-roles.md#user-06-a-page-you-may-not-use-is-refused-by-the-server-too) | Each role sees only its pages, and the server refuses the rest |
| 6 | [STORE-01](04-stores-and-menus.md#store-01-onboard-a-restaurant) | Onboard a store, fill the menu and the delivery area, and go live |
| 7 | [SRCH-01](05-search-and-browsing.md#srch-01-first-visit-asks-for-a-location), [SRCH-03](05-search-and-browsing.md#srch-03-search-by-name-dish-and-cuisine), [SRCH-06](05-search-and-browsing.md#srch-06-store-page-and-the-delivery-fee) | Customers find the store and see the right delivery fee |
| 8 | [ORD-03](06-cart-and-ordering.md#ord-03-place-a-cash-order-for-delivery), [ORD-04](06-cart-and-ordering.md#ord-04-the-total-adds-up) | A cash delivery order, and its total adds up |
| 9 | [ORD-07](06-cart-and-ordering.md#ord-07-double-click-and-network-retry-place-one-order) | A double click places one order |
| 10 | [ORD-09](06-cart-and-ordering.md#ord-09-new-orders-arrive-by-themselves-with-sound), [ORD-10](06-cart-and-ordering.md#ord-10-accept-prepare-ready) | The store sees the order live and moves it on |
| 11 | [DLV-04](09-delivery-and-riders.md#dlv-04-the-rider-gets-an-offer) to [DLV-08](09-delivery-and-riders.md#dlv-08-hand-over-with-the-code) | An operator rider takes the job, picks up, and hands over with the code; the customer follows live |
| 12 | [ORD-11](06-cart-and-ordering.md#ord-11-reject-an-order), [ORD-12](06-cart-and-ordering.md#ord-12-no-answer-in-time) | Reject and no-answer end the order and tell the customer |
| 13 | [PAY-01](07-payments.md#pay-01-pay-by-card-on-the-gateways-page), [PAY-02](07-payments.md#pay-02-card-declined), [PAY-05](07-payments.md#pay-05-refund-when-the-store-rejects-or-cancels) | Card payment, a decline, and a refund |
| 14 | [PAY-07](07-payments.md#pay-07-card-fields-inside-the-app) | No card number anywhere in the databases |
| 15 | [CPN-01](08-coupons.md#cpn-01-operator-coupon-percent-off), [CPN-06](08-coupons.md#cpn-06-a-cancelled-rejected-or-failed-order-gives-the-use-back) | A coupon applies, and comes back when the order does not go ahead |
| 16 | [NTF-01](10-notifications.md#ntf-01-the-bell-fills-as-the-order-moves), [NTF-03](10-notifications.md#ntf-03-order-emails) | The bell and the emails follow the order |
| 17 | [REV-01](11-reviews.md#rev-01-rate-the-store-and-the-rider), [REV-04](11-reviews.md#rev-04-blocked-words-are-masked-and-sent-for-checking) | Rating, and blocked words |
| 18 | [FIN-01](12-finance.md#fin-01-a-finished-order-is-shared-out), [FIN-04](12-finance.md#fin-04-the-payout-list) | The ledger shares out every order, and the payout list matches it |
| 19 | [PRIV-01](13-privacy.md#priv-01-download-my-data), [PRIV-03](13-privacy.md#priv-03-delete-my-account) | Data export, and the deletion request |
| 20 | [OPS-04](14-operations.md#ops-04-events-wait-while-kafka-is-down), [OPS-05](14-operations.md#ops-05-failed-events-see-replay-dismiss) | No event is lost when Kafka is down, and failed events can be replayed |

Before a release, also run [OPS-01](14-operations.md#ops-01-install-a-trial-with-one-command), [OPS-07](14-operations.md#ops-07-back-up-and-restore) and [OPS-08](14-operations.md#ops-08-upgrade-and-roll-back) on a clean machine with the release's own zip, and [OPS-11](14-operations.md#ops-11-kubernetes-a-trial-install-with-the-chart) on a test cluster.

## Checks to run at the end

```js
// 1. Every ledger transaction balances (FIN-01)
use foodstack_ledger
db.ledger_transactions.aggregate([{$project: {s: {$sum: "$legs.amountMinor"}}}, {$match: {s: {$ne: 0}}}]).toArray()   // []

// 2. No event is stuck in an outbox (run in each foodstack_* database)
db.getCollectionNames().filter(c => c.endsWith("_outbox")).forEach(c => print(c, db[c].countDocuments({status: "NEW"})))   // 0 each

// 3. No failed event is left open
use foodstack_settings
db.settings_dead_letters.countDocuments({status: "OPEN"})   // 0, unless OPS-05 left one on purpose
```
