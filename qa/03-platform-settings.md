# 03 Platform settings

[Back to QA index](README.md)

The operator's settings pages: branding, languages and currency, ordering rules, rider pay, the operator's delivery pricing, cities, message wording and the email log. Sign-in options are in AUTH-25. Failed events are in OPS-04.

Database: `foodstack_settings.settings_settings` holds one document per page (`_id`: `branding`, `locale`, `ordering`, `delivery`). Delivery pricing and cities are in `foodstack_geo`.

Every save on these pages names the version it started from. Two people editing the same page at once can never overwrite each other silently (SET-09).

### SET-01 Branding

Who: admin · Where: back office, **Settings > Branding**

Steps
1. Change the name to `QA Eats`, the tagline to `Food for testers`, and the primary colour.
2. Upload a PNG logo.
3. Save, then open (or reload) the customer app.

Expect
- "Branding saved. Customers see it within a minute."
- Within a minute the customer app shows the new name in the header and the browser tab, the logo, and the colour on buttons.

Check
- `settings_settings` `_id: "branding"`: `name: "QA Eats"`, `tagline`, `primaryColor`, `logoMediaId`, `updatedBy` = admin, `updatedAt`, and `version` one higher.
- `foodstack_media.media_assets`: the logo's document is `READY`, with WebP variant URLs in the public bucket.
- `settings_outbox`: `foodstack.settings.SettingsChanged.v1`.
- Redis: `foodstack:settings:cache:branding` is gone right after the save (the next read refills it).

### SET-02 Languages and currency

Who: admin · Where: back office, **Settings > Languages**

Steps
1. Turn on Arabic and Urdu, keep English as the default, and save.
2. In the customer app, open the language menu and choose العربية.
3. Make Arabic the default, save, and open the customer app in a private window.
4. Put English back as the default.

Expect
- "Saved. Customers see the new languages within a minute."
- Step 2: the whole app is in Arabic and right to left, including menus, prices and dates. Dish names show their Arabic text where the store gave one, otherwise English.
- Step 3: a new visitor starts in Arabic.

Check
- `settings_settings` `_id: "locale"`: `languages: ["en","ar","ur"]`, `defaultLanguage`, `currency: "PKR"`, `timezone: "Asia/Karachi"`.
- `settings_outbox`: `SettingsChanged`.

### SET-03 Ordering rules: fees, tax, tips, cash and card

Who: admin · Where: back office, **Settings > Ordering**

Steps
1. Set a service fee of Rs 50, tax 5 %, turn tips on, and save.
2. In the customer app, add a dish to the cart and open checkout.
3. Turn **Cash** off and save, then reload checkout.
4. Turn **Cash** back on.

Expect
- "Saved. The next checkout uses these rules."
- Step 2: checkout shows the service fee, the tax and a tip choice. The total adds up (ORD-04).
- Step 3: cash is no longer offered.

Check
- `settings_settings` `_id: "ordering"`: `serviceFeeMinor: 5000`, `taxBasisPoints: 500`, `tipsEnabled: true`, `cashEnabled`, `cardEnabled`, `acceptTimeoutSeconds`, `priceApprovalRequired`, `directCollectionEnabled`, `creditLimitMinor`, `cardStyle` (`HOSTED_PAGE` or `EMBEDDED`), `savedCardsEnabled`.

### SET-04 Store answer time

Who: admin · Where: **Settings > Ordering**

Steps
1. Set the time a store has to accept an order to 60 seconds and save.
2. Place a cash order at Napoli Slice (ORD-03) and do not accept it.

Expect
- After about a minute the order is rejected for "no answer". The customer sees it, and the store's board drops it (ORD-12).

Check
- `settings_settings.ordering.acceptTimeoutSeconds: 60`. On the order: `acceptBy` is `placedAt` + 60 s.
- Set it back to the default afterwards.

### SET-05 Rider pay and dispatch rules

Who: admin · Where: back office, **Settings > Rider pay**

Steps
1. Set the rider share to 80 % with a minimum of Rs 50, the offer timeout to 60 seconds, the search radius to 5 km, the alert to 5 minutes, and **Ask for the code above** to Rs 0. Save.

Expect
- "Saved. New deliveries use these rules."
- The next delivery job uses them (DLV-04, DLV-08).

Check
- `settings_settings` `_id: "delivery"`: `riderPayBasisPoints: 8000`, `riderPayMinMinor: 5000`, `offerTimeoutSeconds: 60`, `searchRadiusMeters: 5000`, `noRiderAlertSeconds: 300`, `proofCodeAboveMinor: 0`, `locationRetentionDays`.

### SET-06 The operator's delivery pricing

Who: admin · Where: back office, **Delivery pricing**

Steps
1. Set the base charge to Rs 100, Rs 30 per km, a maximum distance of 10 km, and a peak window Friday 19:00–23:00 at 150 %. Save.
2. In the customer app, open Spice Route Gulberg (an operator-delivery branch) with a saved location about 2 km away.
3. Choose a location about 15 km away.

Expect
- Step 2: the delivery charge is about Rs 100 + 30 × the road distance (straight line × 1.3), and 1.5 times that inside the peak window.
- Step 3: "Too far" and no checkout.

Check
- `foodstack_geo.geo_settings` `_id: "operator-delivery"`: `enabled: true`, `baseChargeMinor: 10000`, `perKmMinor: 3000`, `maxDistanceMeters: 10000`, `peakWindows` with the day and times, `peakMultiplierPercent: 150`.
- `geo_outbox`: `foodstack.geo.OperatorDeliveryPricingChanged.v1`. Search then refreshes every operator-delivery branch (SRCH-09).
- Redis: the operator-pricing cache generation (`foodstack:geo:cache:…`) changes.

### SET-07 Cities

Who: admin · Where: back office, **Cities**

Steps
1. Add a city `Karachi` (Arabic name `كراتشي`) with its centre, and save.
2. Edit it and switch it off.

Expect
- The city is listed and can be chosen on a branch (STORE-03) and in coupons (CPN-04).

Check
- `foodstack_geo.geo_cities`: `name: {en: "Karachi", ar: "كراتشي"}`, `centreLat`, `centreLng`, `active`, `updatedBy`, and `version` one higher after the edit.

### SET-08 Message wording

Who: admin · Where: back office, **Settings > Messages**

Steps
1. Open **Order accepted** (group Customers), English.
2. Change the **Heading** to `Good news: {store} has your order` and look at **Preview**.
3. Save, then place and accept an order (ORD-03, ORD-08).
4. Use a placeholder that does not exist, such as `{nonsense}`, and save.
5. Click **Use FoodStack's text**.

Expect
- Step 2: the preview fills the placeholders with sample values as you type. Use **Insert** to add a placeholder.
- Step 3: "Saved. New messages use this text." The customer's bell and email use the new title.
- Step 4: refused. Only the message's own placeholders are allowed.
- Step 5: the original wording is back.

Check
- `foodstack_notification.notification_templates`: a document with `template: "order-accepted"`, `language: "en"`, your `title`, `version`, `updatedBy`. Step 5 deletes it.
- `notification_inbox` for the customer: `title` uses your wording.

### SET-09 Two people edit the same page

Who: admin in two browsers · Where: **Settings > Ordering**

Steps
1. Open the page in both browsers.
2. In the first, change the service fee and save.
3. In the second, change the tax and save.

Expect
- Step 3 is refused with a message that someone else changed the page. After a reload it shows the first browser's change, and saving again works.

Check
- Browser: the second save answers 409 with code `SETTINGS_VERSION_CONFLICT`. A save sent without `If-Match` answers 428 or 400 with `IF_MATCH_REQUIRED`.

### SET-10 Email log

Who: admin (or support) · Where: back office, **Settings > Email log**

Steps
1. Open the page after placing a few orders.
2. Stop Mailpit (`docker stop <mailpit container>`), then make an email happen, for example by placing an order (ORD-03). Do not use a sign-in code: its text is wiped and cannot be sent again.
3. Watch the log, then start Mailpit again and click **Send again** on the failed email.

Expect
- Every email is listed with recipient, subject, status and time. Sign-in codes show their subject only.
- Step 2: the email shows as queued and retried (after 1, 5, 30 and 120 minutes), then failed after 5 tries.
- Step 3: it goes out and Mailpit shows it.

Check
- `notification_deliveries`: `status` moves `QUEUED` → `SENDING` → `SENT`, or `FAILED` with `attempts` and `lastError`. `nextAttemptAt` shows the next retry.
