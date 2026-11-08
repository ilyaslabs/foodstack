# 10 Notifications

[Back to QA index](README.md)

The customer's bell (the in-app notification centre), emails to customers, stores, riders and staff, email choices, push messages, and the operator's wording of each message. The wording itself is tested in SET-08 and the email log in SET-10.

Database: `foodstack_notification`:

| Collection | What it holds |
|---|---|
| `notification_inbox` | One bell message per user and event. `_id` = `<eventId>:<userId>`, with `template`, `title`, `body`, `link`, `createdAt`, `readAt`. Kept 180 days |
| `notification_deliveries` | One email or push per user and event. `_id` = `<eventId>:<userId>:EMAIL` (or `:PUSH`), with `channel`, `template`, `recipient` (encrypted), `subject`, `html`, `text`, `status`, `attempts`, `lastError`, `nextAttemptAt`, `sentAt`. Kept 90 days |
| `notification_preferences` | `_id` = the user, with `orderEmails` and `marketingEmails`. No document means the defaults: order emails on, offers off |
| `notification_devices` | Push devices. `_id` = SHA-256 of the push token, with `userId`, `tokenCipher` (encrypted), `platform` (`WEB`, `ANDROID`, `IOS`), `app` (`web` or `backoffice`), `createdAt`, `seenAt` |
| `notification_templates` | The operator's own wording, `_id` = `<template>:<language>` |

In the local run every email lands in Mailpit (the address the server prints as "Emails sent by this run"). Email links point at `http://localhost:4200/…` for customers and at `…/admin/…` for back-office people.

### Which message goes where

| Event | Who | Template | Bell | Email | Push |
|---|---|---|---|---|---|
| Sign-up | customer | `welcome` | yes | yes (always) | no |
| Order placed (cash), or paid (card) | customer | `order-confirmed` | yes | yes | yes |
| Accepted | customer | `order-accepted` | yes | yes | yes |
| Ready, pickup order | customer | `order-ready-pickup` | yes | yes | yes |
| Picked up | customer | `order-picked-up` | yes | yes | yes |
| Delivered | customer | `order-delivered` | yes | yes | yes |
| Rejected, or no answer | customer | `order-rejected` | yes | yes | yes |
| Cancelled by the store or support | customer | `order-cancelled` | yes | yes | yes |
| Card payment failed | customer | `order-failed` | yes | yes | yes |
| Refund sent | customer | `refund-issued` | yes | yes | yes |
| Rider assigned | customer | `rider-assigned` | yes | no | yes |
| Order placed | the brand owner and the branch's managers | `store-new-order` | kept | yes | yes |
| Rider offer | rider | `rider-offer` | no | no | yes |
| Support assigns a rider | rider | `rider-job-assigned` | kept | no | yes |
| No rider found | admin and support | `dispatch-alert` | kept | no | yes |
| Store over its credit limit | admin, finance and the store's owners | `credit-limit-exceeded` | kept | yes (always) | no |
| Store back within its limit | the store's owners | `credit-limit-restored` | kept | no | no |
| Sign-in or reset code | anyone | `login-code`, `password-reset-code` | no | yes (always) | no |
| Account deletion asked | customer | `account-deletion-scheduled` | no | yes (always) | no |

"Kept" means the message is written to `notification_inbox` but the back office has no bell yet, so only the database shows it. "Always" means the email is sent even when the person switched order emails off.

If a row in a case below disagrees with this table, the table is wrong: tell the developers so both can be fixed.

### NTF-01 The bell fills as the order moves

Who: `customer@demo.foodstack.local` · Where: customer app, the bell in the header

Steps
1. Place a pickup order at Spice Route (ORD-08). As spiceroute, accept it and mark it ready.
2. Watch the bell without reloading. Then open it.
3. Click the newest message.

Expect
- The bell shows a red count that goes up by one for each step: "Order … received", "Spice Route · Gulberg accepted your order", "Order … is ready to collect".
- The list shows the newest first, with the time as "now", "2 minutes ago" and so on. Unread ones stand out.
- Step 3 opens the order page and that message is now read (the count drops by one).

Check
- `notification_inbox`, `userId` = the customer: one document per step with `template` `order-confirmed`, `order-accepted`, `order-ready-pickup`, `link: "/orders/<orderId>"` and no `readAt`. After step 3, the clicked one has `readAt`.
- Network: `GET /api/v1/notifications/unread-count` answers `{"unread": n}`. Opening the bell calls `GET /api/v1/notifications?limit=10`. Clicking calls `POST /api/v1/notifications/<id>/read`.

### NTF-02 Mark all as read, and an empty bell

Who: customer · Where: the bell

Steps
1. With unread messages, open the bell and click **Mark all as read**.
2. Sign in as a new customer who has never ordered (AUTH-01) and open the bell.

Expect
1. The count disappears and every message shows as read.
2. Only the "Welcome to …" message. After marking it read, a customer with no messages sees "Nothing new".

Check
- Step 1: every `notification_inbox` document of the customer has `readAt`. Network: `POST /api/v1/notifications/read-all`.
- A customer cannot read another customer's messages: `POST /api/v1/notifications/<someone else's id>/read` changes nothing.

### NTF-03 Order emails

Who: customer, spiceroute · Where: Mailpit

Steps
1. Place a cash delivery order at Spice Route and follow it to delivered (ORD-03, DLV-04 to DLV-08).
2. Open Mailpit.

Expect
- The customer gets one email for each step in the table above, in the customer's language, with the operator's brand name and colour, a **View order** button and a plain-text part.
- spiceroute (and any manager of the Gulberg branch) gets "New order … for Spice Route · Gulberg" with "… items, PKR …, delivery. Accept it within … minutes." and a link to the live board.
- The order-confirmed email of a delivery order carries the delivery code ("Your delivery code is …").

Check
- `notification_deliveries`: one `EMAIL` document per message with `status: "SENT"`, `attempts: 1`, `sentAt`, `subject` as in Mailpit and `recipient` an unreadable encrypted string (never the plain email address).
- Delivering the same Kafka event again adds no second email: the `_id` is made from the event id.

### NTF-04 Turn order emails off

Who: customer · Where: customer app, **Account > Emails**

Steps
1. Switch off **Order updates by email** ("Sign-in codes are always sent.").
2. Place and accept an order.
3. Ask for a sign-in code (AUTH-08).
4. Switch it on again.

Expect
- Step 2: the bell gets every message as before. Mailpit gets no order email.
- Step 3: the code email still arrives.
- **Offers and news** is off by default and can be switched on. FoodStack v1 sends no offer emails yet, so it only records the choice.

Check
- `notification_preferences`, `_id` = the customer: `orderEmails: false`, `marketingEmails: false`, `updatedAt`. Step 4: `orderEmails: true`.
- During step 2, no new `notification_deliveries` document for this customer, but new `notification_inbox` ones.
- Network: `GET` and `PUT /api/v1/notifications/preferences`.

### NTF-05 Code emails keep the code secret

Who: tester · Where: Mailpit, mongosh, **Settings > Email log**

Steps
1. Ask for a sign-in code and a password-reset code (AUTH-08, AUTH-11).
2. Look at the emails in Mailpit and in the email log.

Expect
- Mailpit: "… is your FoodStack Demo sign-in code", with the code and how many minutes it lasts.
- The email log shows the subject "Your sign-in code" without the code, and has no **Send again** for it.

Check
- `notification_deliveries` for the code email: `template: "login-code"`, `subject: "Your sign-in code"` (no code), `sealedSubject` encrypted. Once sent, `html` and `text` are removed, so the code is nowhere in the database in plain text.
- `POST /api/v1/notifications/deliveries/<id>/retry` for it answers 409 `DELIVERY_NOT_FAILED`.

### NTF-06 No mail server set up

Who: tester · Where: a server started without `SMTP_HOST` (for example a fresh `install.sh` with the email step skipped, OPS-01)

Steps
1. Place an order.
2. Open **Settings > Email log**.

Expect
- The order works as usual and the bell fills. The log lists the emails as skipped.

Check
- `notification_deliveries`: `status: "SKIPPED"`, `lastError: "Email is not set up (SMTP_HOST)"`. After setting up email and restarting, **Send again** sends them.

### NTF-07 Push messages on the rider's phone

Who: rider · Where: back office on a phone, **Deliveries**, with push set up

Push needs a Firebase project. Start the server with `FCM_SERVICE_ACCOUNT` (the key file or its JSON), `FCM_WEB_CONFIG` (the web app's config as one line of JSON) and `FCM_VAPID_KEY`. Without them, push is off and nothing about push shows in the apps. That is also a pass.

Steps
1. Without push set up, open the rider page and the customer's order page.
2. With push set up, open the rider page. Click **Turn on** and allow notifications.
3. Put the app in the background and make a delivery offer happen (DLV-04).
4. Tap the notification.
5. As the customer, on a delivery order page, tap **Turn on** under "Get a notification when your rider is on the way." Then have the rider pick the order up.
6. Sign out on that phone.

Expect
1. No push banner anywhere. `GET /api/v1/devices/push-config` answers `{"enabled": false}`.
2. The banner goes away.
3. The phone shows "New delivery: earn Rs …" within seconds.
4. It opens the rider page with the offer.
5. The customer's phone shows "Order … is on its way".
6. That phone gets no more pushes for the signed-out person.

Check
- `notification_devices`: one document for the phone, `userId` = the rider, `platform: "WEB"`, `app: "backoffice"`, `tokenCipher` encrypted (the token is never stored in plain text). Signing in again updates `seenAt`, not a second document.
- `notification_deliveries`: `channel: "PUSH"`, `template: "rider-offer"`, `app: "backoffice"`, `link` to the rider page, `status: "SENT"`. A failed push is tried again after 10 and 30 seconds, then `FAILED`.
- Step 6: `POST /api/v1/devices/remove` and the device document is gone. A token the push service says is no longer valid is also removed.

### NTF-08 Store, support and finance alerts

Who: spiceroute, admin, support, finance · Where: Mailpit, mongosh (and push, if set up)

Steps
1. Place an order at Spice Route.
2. With no rider online, place an operator-delivered order and wait for the dispatch alert (DLV-10).
3. Push Napoli Slice over its credit limit (PAY-12), then settle it (FIN-05).

Expect
1. Mailpit: spiceroute gets "New order …".
2. No email. With push set up, admin and support get "No rider for order …" on their phones. Without push, the alert shows on **Dispatch** (DLV-10).
3. Mailpit: admin, finance and Napoli's owners get "Napoli Slice · DHA Phase 5 is over its credit limit". Settling sends no email.

Check
- `notification_deliveries`: `store-new-order` and `credit-limit-exceeded` emails, with the back office's link (`…/admin/…`).
- `notification_inbox`: a `dispatch-alert` document for every admin and support user, and after settling a `credit-limit-restored` document for each of the store's owners.

### NTF-09 Messages in the customer's language

Who: customer · Where: customer app (Arabic and Urdu on, SET-02)

Steps
1. Switch the app to Urdu and place an order.
2. Have the store accept it.

Expect
- The bell messages and the emails for this order are in Urdu, right to left, with prices formatted for Urdu.
- The store's "New order" email stays in the operator's default language.

Check
- `notification_inbox.title` is in Urdu for these messages.

### NTF-10 A customer's own wording of a message

The operator changes a message's wording in **Settings > Messages** (SET-08). After any change, repeat NTF-01 and NTF-03 and confirm the bell and the email both use the new wording, in the language that was changed, and that other languages keep FoodStack's text.
