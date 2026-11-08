# 04 Stores and menus

[Back to QA index](README.md)

Bringing a restaurant onto the platform and keeping it right: the brand and its branches, managers, profile, menu, branch prices and approvals, sold-out marks, opening hours, delivery zones, going live, pausing, commission and search placement.

Database: `foodstack_store`, plus `foodstack_geo.geo_zones` for zones. Events: `store_outbox` and `geo_outbox`, Kafka `foodstack.store.events` and `foodstack.geo.events`. Search follows every change (SRCH-09).

Every store is a **branch** of a **brand**. The brand owns the name, logo and menu. Each branch has its own address, hours, delivery zones, prices (when allowed), sold-out marks and orders.

## Onboarding

### STORE-01 Onboard a restaurant

Who: admin · Where: back office, **Stores > Onboard restaurant**

Steps
1. Fill in brand name `QA Grill`, branch name `Model Town`, legal name `QA Grill (Pvt) Ltd`, tax number, cuisines `bbq, pakistani`, a short description, street address, area, city Lahore, latitude `31.4834` and longitude `74.3256`, preparation time 20 minutes, and commission 15 % plus Rs 0.
2. Click **Onboard restaurant**.

Expect
- The new branch page opens with status **Draft** and a box "Before this branch can go live" listing what is missing: a dish on the Menu tab, and a delivery zone on the Delivery tab (or pickup on).
- The customer app does not show the branch yet.

Check
- `store_brands`: `name: "QA Grill"`, `cuisines`, `commission: {percentBasisPoints: 1500, fixedMinor: 0}`, `branchCount: 1`, `searchBoost: 0`, `branchPromotionsAllowed: false`, `createdBy` = admin.
- `store_stores`: `brandName`, `branchName: "Model Town"`, `slug: "qa-grill-model-town"`, `status: "DRAFT"`, `location` (`type: "Point"`, `coordinates: [74.3256, 31.4834]`, longitude first), `prepMinutes: 20`, `timezone: "Asia/Karachi"` (found from the location), `cityId` = Lahore's id, `deliveryMode: "SELF"`, `paymentCollection: "OPERATOR"`, and default `openingHours` of 11:00–23:00 every day.
- `store_slugs`: `_id: "qa-grill-model-town"` with the branch's `storeId`.
- `store_outbox`: `BrandChanged` and `StoreChanged`, both `SENT`.
- Elasticsearch: `GET foodstack_search_stores/_doc/<storeId>` answers `found: false`, because a draft is not indexed.

### STORE-02 A second branch of the same brand gets its own web address

Onboarding the same brand name again makes a *new* brand. Add branches to an existing brand from its **Brand** tab.

Who: admin · Where: QA Grill's **Brand** tab, **Add a branch**

Steps
1. Add a branch named `Model Town` again, at another point.
2. Add a branch named `Johar Town`.

Expect
- Both are made. The first gets the address `qa-grill-model-town-2`.

Check
- `store_slugs` has `qa-grill-model-town-2` and `qa-grill-johar-town`. `store_brands.branchCount: 3`.

### STORE-03 Profile, images and order tickets

Who: admin (also the brand owner for their own branch) · Where: the branch's **Profile** tab

Steps
1. Upload a logo (square, at least 512 px) and a cover photo (wide, at least 1600 px).
2. Change the description and the city, set order tickets to 58 mm paper, and save.
3. Try to set the web address to `QA Grill!!`.

Expect
- "Branch saved." and "Brand saved." The images show at once.
- Step 3: "Check the web address."

Check
- `store_brands`: `logoMediaId` and `coverMediaId` set. `foodstack_media.media_assets` has both `READY`, with variant URLs in the public bucket.
- `store_stores.ticketPaperMm: 58`.

### STORE-04 Give a brand owner and a branch manager access

Who: admin · Where: the **Managers** tab

Steps
1. On QA Grill Model Town, give access to `qa.brand@example.test` as brand owner (from the brand), and to `qa.branch@example.test` as manager of this branch only. Set starting passwords.
2. Sign in as each.

Expect
- The brand owner sees **Branches** with all QA Grill branches. The branch manager sees **My store** with one branch.
- Neither sees **Stores** or other brands.

Check
- `identity_users`: the brand owner has `roles: ["BRAND_OWNER"]` and `brandIds` with QA Grill's id. The manager has `roles: ["STORE_OWNER"]` and `storeIds` with the branch id.

## Menu

### STORE-05 Build the menu

Who: the brand owner `qa.brand@example.test` · Where: branch, **Menu** tab

Steps
1. Click **Start the menu**, then add the section `Grill`.
2. Add the dish `Chicken tikka`, Rs 650, with a photo, the tag **Spicy**, and an option group `Size` (customer picks at least 1, at most 1) with `Half` (+Rs 0) and `Full` (+Rs 400).
3. Add `Seekh kebab`, Rs 450, sold at **Some branches**: Model Town only.
4. Reorder the two dishes.

Expect
- "Section added.", "Dish added." and "Order saved." The menu shows sections and dishes in the order you set.
- Seekh kebab shows "Not at this branch" on Johar Town's menu.

Check
- `store_menu_sections`: one document with `brandId`, `name.en: "Grill"`, `sortOrder`.
- `store_menu_items`: `priceMinor: 65000`, `sectionId`, `imageMediaId`, `sortOrder`. Seekh kebab has `branchIds` = [Model Town's id]. An item sold everywhere has empty `branchIds`.
- `store_outbox`: `foodstack.store.MenuChanged.v1` with the brand id as `subject`.
- Redis: `foodstack:store:menu:<storeId>` is gone after each save, for every branch of the brand.

### STORE-06 Sold out at one branch

Who: the branch manager `qa.branch@example.test` · Where: **My store > Menu**

Steps
1. Mark Chicken tikka **Sold out** at Model Town.
2. Open both branches in the customer app.
3. Mark it **In stock** again.

Expect
- Model Town shows Chicken tikka as sold out and it cannot be added to the cart. Johar Town is not affected.
- A cart that already holds it shows a problem at checkout (ORD-05).

Check
- `store_item_overrides`: `_id: "<storeId>:<itemId>"`, `soldOut: true`, `updatedBy`.
- `store_outbox`: `ItemAvailabilityChanged` with the branch id.
- Search: sold-out dishes drop out of dish search for that branch (SRCH-03).

### STORE-07 Delete a dish and a section

Who: the brand owner · Where: **Menu**

Steps
1. Delete Seekh kebab, then delete an empty section.

Expect
- "Delete this dish?" then "Dish deleted." The dish disappears from every branch.

Check
- `store_menu_items`: the document stays, with `deletedAt` set, so past orders still show it.

### STORE-08 Branch prices, with approval

Who: admin, then the branch manager · Where: **Settings > Ordering**, then **My store > Menu**

Steps
1. As admin, switch on price approval on **Settings > Ordering**.
2. As the Model Town manager, open **Prices at Model Town** and set Chicken tikka to Rs 700. Save.
3. As admin, open **Price approvals** and approve it.
4. Repeat with Rs 750 and **Reject**.

Expect
- Step 2: "Branch prices saved." The customer app still shows Rs 650 until the change is approved.
- Step 3: Model Town now shows Rs 700. Johar Town still shows Rs 650.
- Step 4: nothing changes for customers.

Check
- `store_price_changes`: after step 2, `status: "PENDING"`, `currentMinor: 65000`, `proposedMinor: 70000`, `requestedBy`. After step 3, `status: "APPROVED"` with `decidedBy` and `decidedAt`. Step 4 ends `REJECTED`.
- `store_item_overrides` for Model Town: `priceOverrideMinor: 70000` after step 3.
- With approval off, a branch price applies at once and no `store_price_changes` document is made.
- On Kubernetes with the modules split (`split` or `microservices`), **Price approvals** lists the change and approving answers 200, not 404 (`/api/v1/price-changes` is routed to the store module).

## Hours and closing

### STORE-09 Opening hours and holidays

Who: the branch manager · Where: **Hours** tab

Steps
1. Set Monday to Sunday 11:00–23:00, add a holiday closure for tomorrow with the reason `Eid`, and click **Save hours**.
2. Set today's hours so the branch is closed right now.

Expect
- "Hours saved."
- Step 2: the customer app shows the branch as **Closed now**, still listed after the open branches. Checkout refuses (ORD-05).

Check
- `store_stores`: `openingHours` per day and `closures` with the date and reason.
- `store_outbox`: `StoreChanged`.

### STORE-10 Stop taking orders for a while

Who: the branch manager · Where: the branch header switch **Taking orders**

Steps
1. Switch it off, with the reason `Back at 6 pm`.
2. Look at the branch in the customer app.
3. Switch it back on.

Expect
- "Stop taking orders?" then the header says "Taking no orders".
- Customers see the branch closed, with "Back at 6 pm".
- Step 3: "Taking orders again."

Check
- `store_stores`: `temporarilyClosed: true`, `closedReason: "Back at 6 pm"`, `closedAt`. Then `false`.
- `store_outbox`: `StoreAvailabilityChanged`.

## Delivery zones

### STORE-11 Draw delivery zones for a store that delivers itself

Who: the branch manager · Where: **Delivery** tab (with **Platform riders deliver for this branch** off)

Steps
1. Draw zone `Near` on the map around the branch: about 3 km across, charge Rs 100, minimum order Rs 500, free above Rs 2,000.
2. Add zone `Far` as a circle with a radius of 6 km, charge Rs 200.
3. Click **Save zones**.
4. Draw a zone whose edges cross (a figure of eight), and save.

Expect
- "Delivery zones saved." If the branch itself is outside every zone: "Saved. Note: the branch itself is outside every zone."
- Step 4 is refused.
- In the customer app, a point inside `Near` is quoted Rs 100, a point in `Far` only Rs 200, and a point outside both "Out of area" (SRCH-06).

Check
- `foodstack_geo.geo_zones`: one document per zone, with `storeId`, `name`, `sortOrder`, `area` (a GeoJSON polygon), `chargeMinor: 10000`, `minOrderMinor: 50000`, `freeDeliveryAboveMinor: 200000`. A circle is saved as a polygon, with `centerLat`, `centerLng` and `radiusMeters` kept.
- `geo_outbox`: `foodstack.geo.ZoneChanged.v1` with the branch id.
- Step 4: 400 with code `ZONES_INVALID`.

### STORE-12 Hand delivery to the platform's riders

Who: admin · Where: the branch's **Delivery** tab

Steps
1. Switch **Platform riders deliver for this branch** on and save.

Expect
- The zone editor gives way to the operator's pricing (SET-06). Customers are quoted the operator's rate.

Check
- `store_stores`: `deliveryMode: "OPERATOR"`, `operatorDeliveryAllowed: true`.

## Going live

### STORE-13 Go live only when ready

Who: admin · Where: QA Grill Model Town, header

Steps
1. With no dish and no zone, click **Go live**.
2. Add a dish (STORE-05) and a zone (STORE-11), or switch pickup on, then click **Go live** again.
3. Search for the branch in the customer app near its location.

Expect
- Step 1 is refused, and the readiness box lists what is missing: "Add at least one dish on the Menu tab" and "Add a delivery zone on the Delivery tab, or switch on pickup".
- Step 2: "The branch is live. Customers nearby can find it." The status shows **Live**.
- Step 3: the branch appears within a couple of seconds.

Check
- Step 1: 409 with code `STORE_NOT_READY` and the missing items (`LOCATION`, `MENU`, `DELIVERY_AREA`).
- `store_stores.status: "LIVE"`. `store_outbox`: `StoreChanged`.
- Elasticsearch: `foodstack_search_stores/_doc/<storeId>` now exists, with `deliveryArea`, `cuisines`, `dishes.en` and `location`.

### STORE-14 Pause a live branch

Who: admin · Where: header, **Pause**

Steps
1. Click **Pause**. Open the branch's web address in the customer app.
2. Click **Go live** again.

Expect
- "The branch is hidden from customers." Search no longer shows it. Its page gives 404 to customers but still opens for staff and its own managers.

Check
- `store_stores.status: "PAUSED"`, and the Elasticsearch document is gone.

### STORE-15 A branch manager cannot go live alone

Who: the branch manager of a draft branch · Where: **My store**

Expect
- No **Go live** button. When the branch is ready the box says "Ready. The platform team will make your branch live."

## Commercial terms

### STORE-16 Commission

Who: admin · Where: **Brand** tab, **Change commission**, then a branch's **Profile > Platform settings**

Steps
1. Set the brand's commission to 12 % plus Rs 20 per order and save.
2. Override one branch with 10 % and save.
3. Complete an order at each branch (ORD-03).

Expect
- Each order uses its branch's rate.

Check
- `store_brands`: the brand commission. `store_stores.commissionOverride` on the overridden branch.
- `foodstack_order.order_orders.commission`: `basisPoints: 1200, fixedMinor: 2000` or `basisPoints: 1000`.
- `foodstack_ledger.ledger_transactions`, `order:<orderId>:done`: a `COMMISSION` leg on the store's account and the opposite on `operator:commission` (FIN-01).

### STORE-17 Search placement

Who: admin · Where: **Brand** tab, **Search placement**

Steps
1. Set QA Grill to **Top** and save.
2. Search near a point where QA Grill and another open restaurant are about the same distance away.

Expect
- "Saved. Search shows the new placement shortly." QA Grill now comes first among open restaurants (SRCH-08).
- A brand owner cannot see or change this card.

Check
- `store_brands.searchBoost: 3`. `store_outbox`: `BrandChanged`.
- Elasticsearch documents of every QA Grill branch: `searchBoost: 3`.
- `PUT /api/v1/brands/<id>/search-boost` with a brand owner's token answers 403.

### STORE-18 Branch managers' coupons

Who: admin or the brand owner · Where: **Brand** tab, **Branch promotions**

Steps
1. A new brand starts with branch promotions off. As the branch manager, open **Coupons**: only the brand's coupons are listed and there is no way to add one.
2. As the brand owner, switch **Branch promotions** on.
3. As the branch manager, open **Coupons** again.

Expect
- Step 2: "Branch managers can run coupons now."
- Step 3: the manager can make coupons for their own branch (CPN-05). Switching it off again: "Only brand owners run coupons now."

Check
- `store_brands.branchPromotionsAllowed`: `false` by default, `true` after step 2.

### STORE-19 Who collects card payments

See PAY-10 and PAY-11.

### STORE-20 Store changes are kept in the audit log

Who: admin · Where: **Audit log**

Expect
- Each change in this file has a line with who, when, the endpoint and before and after (USER-10).
