# 13 Privacy

[Back to QA index](README.md)

A customer downloads everything FoodStack keeps about them, and deletes their account. Personal details that are stored are encrypted, and card numbers never reach FoodStack (PAY-07).

### PRIV-01 Download my data

Who: `customer@demo.foodstack.local` (with orders, addresses, a review and notifications) · Where: customer app, **Account > Your data**

Steps
1. Click **Download my data**.
2. Open the file.

Expect
- The page says "Download a copy of everything we keep about you: your account, addresses, orders, reviews and notifications."
- A file named `my-data-<today>.json` downloads. It holds `exportedAt`, the account (email, name, phone, roles, when it was made), every saved address, every order in full (lines, totals, address, timeline), the customer's reviews and rider ratings, the bell messages, the email choices and the push devices (platform and date, not the token).
- Nothing about other people: no rider's phone, no other customer.

Check
- Network: four calls, all for the signed-in person only: `GET /api/v1/me/export`, `/api/v1/orders/mine/export`, `/api/v1/reviews/mine/export`, `/api/v1/notifications/mine/export`. Signed out, each answers 401.
- The file has no password hash, no refresh tokens, no push tokens and no card numbers.

### PRIV-02 Personal details are encrypted in the database

Who: tester · Where: mongosh

Steps
1. Look at the customer in `foodstack_identity.identity_users`, an order in `foodstack_order.order_orders`, a delivery job in `foodstack_dispatch`, and an email in `foodstack_notification.notification_deliveries`.

Expect
- `identity_users`: `emailCipher` is unreadable, `emailIndex` is a hash (used to find the account by email), `passwordHash` starts `{argon2}$argon2id$`.
- `order_orders.contactPhone`, the dispatch job's `contactPhoneCipher`, `notification_deliveries.recipient` and `notification_devices.tokenCipher` are unreadable.
- Search the databases for the customer's email in plain text: nothing.

```js
// run in each database: foodstack_identity, foodstack_order, foodstack_dispatch, foodstack_notification
db.getCollectionNames().forEach(c => { const n = db[c].countDocuments({$where: "JSON.stringify(this).includes('customer@demo.foodstack.local')"}); if (n) print(c, n) })
```

### PRIV-03 Delete my account

Who: a customer made for this case (AUTH-01) with an order, an address, a review with a photo and a saved card · Where: customer app, **Account > Your data**

Steps
1. Click **Delete my account**. Read the dialog, then click **Delete account**.
2. Try to use the app in another tab where the same customer was signed in.
3. Open Mailpit.

Expect
- The page says "Your account and personal details are deleted 14 days after you ask. Past orders stay for the records, without your name, phone or address."
- The dialog **Delete your account?** says you will be signed out on every device and what is deleted, and that signing in before then keeps the account.
- After confirming: **Your account will be deleted** "Your account will be deleted on <date>. We have emailed you the details. Sign in before then if you want to keep it." Then you are signed out.
- Step 2: the other tab is signed out too.
- Step 3: "Your account will be deleted" email with the date. It is sent even if order emails are off.

Check
- `identity_users`: `deletionRequestedAt` = now, `deleteAfter` = now + 14 days, `status` still `ACTIVE`, `tokenVersion` up by one.
- `identity_refresh_tokens` of the user are revoked. Redis: `foodstack:identity:revoked:<userId>` is set.
- `identity_outbox`: `AccountDeletionScheduled`.
- Network: `POST /api/v1/me/deletion` answers `deleteAfter` (seconds) and `graceDays: 14`. Asking again keeps the first date.

### PRIV-04 Changed my mind

Who: the customer from PRIV-03, within the 14 days · Where: customer app

Steps
1. Sign in again.

Expect
- Sign-in works and the account is kept, with its orders and addresses.

Check
- `identity_users`: `deleteAfter` and `deletionRequestedAt` are gone.

### PRIV-05 The deletion runs

Who: tester · Where: mongosh, then the apps

The server looks for accounts due for deletion every 30 minutes (2 minutes after start). To test quickly, start the local run with `FOODSTACK_IDENTITY_DELETIONSWEEP=PT1M` in front of the `mvn` command, request a deletion (PRIV-03), then move the date back:

```js
use foodstack_identity
db.identity_users.updateOne({_id: "<userId>"}, {$set: {deleteAfter: new Date(Date.now() - 60000)}})
```

Steps
1. Wait a minute or two.
2. Try to sign in with the old email and password, and ask for a sign-in code.
3. Sign up again with the same email.
4. Open the store page where the customer wrote a review, and the order in the back office.

Expect
2. "That email and password do not match." No code email arrives (AUTH-10).
3. Works: a brand new account, with no old orders.
4. The review stays, under "Deleted user", without the photo (REV-09). The order stays in the back office with the customer shown as "Deleted user", no phone, no address line.

Check
- `foodstack_identity.identity_users`: only the id is left with `status: "DELETED"`, `deletedAt`, empty `roles`, no `emailCipher`, `emailIndex`, `passwordHash` or `displayName`. The user's `identity_addresses`, `identity_refresh_tokens` and login key are removed. `identity_outbox`: `UserDeleted`.
- `foodstack_order.order_orders` of the customer: `customerName: "Deleted user"`, no `contactPhone`, line notes empty, `destination.addressLine` and `destination.instructions` empty, `destination.lat` and `lng` rounded to two decimals (about 1 km).
- `foodstack_dispatch`: the jobs of those orders have `customerName: "Deleted user"`, no `contactPhoneCipher`, `dropoffAddress` or `instructions`, and rounded drop-off points.
- `foodstack_notification`: no `notification_inbox`, `notification_deliveries`, `notification_devices` or `notification_preferences` left for the user.
- `foodstack_review.review_reviews`: `customerName: "Deleted user"`, no `photo`. The rating still counts.
- `foodstack_payment.payment_customers`: the customer's gateway reference is gone, and the sandbox forgot the saved cards (`payment_sandbox_cards`).
- The ledger keeps every transaction: it holds no personal details.

### PRIV-06 Staff accounts cannot delete themselves

Who: spiceroute, or any back-office user · Where: the API

Steps
1. Signed in as spiceroute, call `POST /api/v1/me/deletion`.
2. Sign in to the customer app as spiceroute, open **Account**, click **Delete my account**, then **Delete account**.

Expect
1. 409 `ACCOUNT_HAS_STAFF_ROLES`: "This account has back-office access; ask the operator to close it".
2. "This account has access to the back office. Ask the operator to close it." An admin blocks the account instead (USER-08).
