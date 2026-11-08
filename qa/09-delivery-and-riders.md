# 09 Delivery and riders

[Back to QA index](README.md)

Riders and their papers, going online, offers, accepting, pickup, the delivery code, live tracking, earnings, support's dispatch page, and stores that deliver with their own riders.

Databases: `foodstack_dispatch` (`dispatch_jobs`, one per delivery order, `_id` = order id; `dispatch_active`, unfinished jobs only) and `foodstack_rider` (`rider_riders`, `rider_onboarding`, `rider_assignments`, `rider_locations`, `rider_earnings`). Events: Kafka `foodstack.dispatch.events` and `foodstack.rider.events`. Live positions are in Redis.

The rider pages are in the back office at `/admin/rider`. Use a phone, or the browser's phone view. To move a rider without walking, set the browser's location in F12 > Sensors.

A delivery job moves like this:

```
platform riders:  SEARCHING → OFFERED → ASSIGNED → AT_STORE → PICKED_UP → DELIVERED
store's riders:   WAITING → ASSIGNED → … the same
also:             OFFERED → SEARCHING (declined or timed out), ASSIGNED → SEARCHING (rider gives up),
                  any → WAITING (support hands it back to the store), any → CANCELLED (order cancelled)
```

Before you start: set rider pay and dispatch rules (SET-05), with **Ask for the code above** at Rs 0 so every delivery needs a code.

## Riders

### DLV-01 Add an operator rider

Who: admin · Where: back office, **Riders > Add a rider**

Steps
1. Fill **Full name** `QA Rider`, **Phone** `+92 300 5556677`, **Email (signs in with it)** `qa.rider@example.test`, **First password**, **Vehicle** Motorbike, **Number plate** `LEB-1234`, **City** Lahore. Click **Add rider**.

Expect
- The rider is listed as **Offline**, with **Papers missing**.
- They can sign in to the back office and see only **Deliveries** and **Earnings**.

Check
- `rider_riders`: `kind: "OPERATOR"`, `name`, `phoneCipher` (encrypted), `vehicle: "MOTORBIKE"`, `plate`, `cityId`, `status: "ACTIVE"`, `online: false`, `offers: 0`, `accepted: 0`, `deliveries: 0`.
- `foodstack_identity.identity_users`: a user with `roles: ["RIDER"]`. The rider document's `_id` is that user's id.

### DLV-02 Rider papers

Who: admin · Where: the rider's page, **Papers**

Steps
1. **Add a document**: **National ID card**, document number `35202-1234567-1`, front and back photos, expiry next year.
2. Click **Mark checked** after confirming the photos are clear.
3. **Add payout details**: **Bank account**, provider `City Bank`, account holder, account number.
4. Look at the riders list.

Expect
- The document shows "Ends in 4567" (only the last four digits), **Checked**, and the expiry.
- After step 3 the rider is no longer marked **Papers missing**.
- Photos open through short-lived links only.

Check
- `rider_onboarding`, `_id` = the rider: `documents`, each with `type` (`NATIONAL_ID`, `PASSPORT`, `DRIVING_LICENCE`, `VEHICLE_REGISTRATION` or `OTHER`), `numberCipher` (**encrypted**), `numberLast4`, `frontMediaId`, `backMediaId`, `expiresOn`, `addedBy`, and after checking `verifiedBy` and `verifiedAt`. `payout`: `method` (`BANK` or `MOBILE_WALLET`), `accountName`, `provider`, `accountCipher` (**encrypted**), `accountLast4`.
- `foodstack_media.media_assets`: the photos are in the **private** bucket, owned by the rider document.

### DLV-03 Go online

Who: `rider@demo.foodstack.local` · Where: `/admin/rider`

Steps
1. Switch **Offline** to **Online** and allow location (set it near Spice Route Gulberg: 31.512, 74.344).
2. Click **Turn on** for notifications.
3. Wait two minutes with the page open.

Expect
- "Waiting for a delivery. Stay online. Offers show here and your phone rings when one comes in."
- If location is blocked: "Allow location for this site to go online."

Check
- `rider_riders`: `online: true`, `lat`, `lng`, `lastSeenAt` updating, `idleSince`.
- Redis: `foodstack:rider:pos:<riderId>` (the last point, expires in 10 minutes) and the rider in a GEO set `foodstack:rider:geo:<cell>` (`ZRANGE foodstack:rider:geo:<cell> 0 -1`).
- Network: `POST /api/v1/rider/me/locations` about every 10 seconds.
- `foodstack_notification.notification_devices`: a device for the rider (only when push is set up, NTF-05).

## A delivery by the platform's riders

### DLV-04 The rider gets an offer

Who: customer, spiceroute, rider · Where: three browsers

Steps
1. With the rider online near the store, place a delivery order at Spice Route Gulberg (ORD-03).
2. As spiceroute, **Accept** it with 10 minutes' preparation.
3. Watch the rider's screen.

Expect
- Within a few seconds the rider sees **New delivery**: the store, the drop-off area, the distance, what they will earn, and whether there is **Cash** to collect or it was **Paid online**, with **Decline** and **Accept**. The phone rings if notifications are on.
- The customer's page says "Finding a rider for your order…".

Check
- `dispatch_jobs`, `_id` = the order id: `mode: "OPERATOR"`, `status: "OFFERED"`, `offerRiderId` = the rider, `offerExpiresAt`, `pickupLat/Lng`, `dropoffLat/Lng`, `dropoffAddress`, `instructions`, `deliveryChargeMinor`, `cashToCollectMinor` (the order total for cash, 0 for card), `codeHash` (never the code itself), `radiusMeters`, `searchStartedAt`.
- `dispatch_active`: the same `_id`, `status: "OFFERED"`.
- Redis: `foodstack:dispatch:offer:<riderId>` exists while the offer is open.
- `dispatch_outbox`: `foodstack.dispatch.RiderOffered.v1`.

### DLV-05 Decline, or let the offer run out

Who: rider, rider2 · Where: rider pages, both online

Steps
1. Make an offer (DLV-04). The first rider clicks **Decline**.
2. Make another and let the offer time run out (60 s in SET-05).

Expect
- Each time the job goes to the other online rider.
- If nobody else is free, the job keeps searching, and the search area grows.

Check
- `dispatch_jobs.triedRiderIds` lists the riders who declined or let it lapse. `rider_riders.offers` counts offers.

### DLV-06 Accept and go to the store

Who: rider · Where: rider page

Steps
1. Click **Accept**.
2. Open **Directions**.
3. At the store, click **I'm at the store**.

Expect
- The job card shows the progress steps, the store's address, the drop-off, any cash to collect, and **Code needed** when a code is required.
- The customer's page: "{name} is bringing your order" with the vehicle, then "Your rider is on the way to Spice Route · Gulberg", and a map with the rider.
- The store's board shows the rider's name on the order.

Check
- `dispatch_jobs`: `status: "ASSIGNED"`, `riderId`, `riderName`, `vehicle`, `assignedAt`, then `"AT_STORE"` with `atStoreAt`.
- `rider_assignments`: one document for the job, with `riderId`, `customerId`, `storeId` and the pickup and drop-off points (it is removed when the job ends).
- Redis: the rider is no longer in the GEO set (busy).
- `order_orders.timeline`: "Rider Bilal Ahmed is on the way to the store". `order_orders.rider` holds the name and vehicle.
- `dispatch_outbox`: `RiderAssigned`. The customer gets a `rider-assigned` message (NTF-01).

### DLV-07 Pick up and follow live

Who: rider, customer · Where: both

Steps
1. The store marks the order **Mark ready**. The rider clicks **Picked up**.
2. Move the rider's location towards the customer in F12 > Sensors, a few times.

Expect
- The customer's page says **On the way** and "Arrives in about {n} min", and the rider's dot moves on the map within a few seconds of each move. When near: "Arriving now".
- The delivery code shows on the customer's page under **Delivery code**: "Give this code to the rider when your order arrives." It is also in the order email.

Check
- `dispatch_jobs`: `status: "PICKED_UP"`, `pickedUpAt`. `order_orders.status: "PICKED_UP"`.
- `rider_locations`: samples with `riderId`, `orderIds`, `lat`, `lng`, `at`, and `expireAt` (deleted after the retention days of SET-05).
- Network (customer): `GET /api/v1/riders/tracking/<orderId>` answers the position, `etaSeconds` and `remainingMeters`. `GET /api/v1/riders/tracking/<orderId>/stream` stays open.
- `order_orders.deliveryCodeCipher` holds the code encrypted. The store's view of the order never includes the code.

### DLV-08 Hand over with the code

Who: rider · Where: rider page, **Delivered…**

Steps
1. Enter a wrong 4-digit code.
2. Try a wrong code 5 times in a row.
3. Enter the right code (from the customer's page) and the cash collected, optionally add a photo, and confirm.

Expect
- Step 1: refused, "Enter the 4-digit code the customer has."
- Step 2: further tries are refused for 10 minutes.
- Step 3: the job closes. The rider's **Earned today** goes up. The customer's page says **Delivered** and asks to rate the order (REV-01).

Check
- Step 1: 400 with code `DELIVERY_CODE_WRONG`.
- `dispatch_jobs`: `status: "DELIVERED"`, `closedAt`, `proof: "CODE"` (or `PHOTO` with `proofPhotoUrl`), `cashCollectedMinor`, `earningMinor`, `distanceMeters`.
- `rider_earnings`, `_id` = the order id: `riderId`, `orderNumber`, `storeName`, `earningMinor`, `tipMinor`, `cashCollectedMinor`, `distanceMeters`.
- `rider_riders`: `deliveries` and `accepted` up by one, and the rider is free again.
- `ledger_transactions`, `delivery:<orderId>`: the rider's `EARNING` and, for cash, the cash moved onto the rider (FIN-02).
- `order_orders.status: "DELIVERED"`.

### DLV-09 The rider can't deliver

Who: rider · Where: rider page, an accepted job before pickup

Steps
1. Click **Can't deliver**, give a reason ("Why can you not do it?"), and confirm.

Expect
- "Support and the store are told, and the delivery goes to another rider." The job is offered to someone else.

Check
- `dispatch_jobs`: back to `"SEARCHING"`, rider cleared, `triedRiderIds` includes the rider. `dispatch_outbox`: `RiderUnassigned`.

### DLV-10 Nobody is free: support is alerted

Who: customer, store, support · Where: with no rider online

Steps
1. Place and accept a delivery order with every rider offline.
2. Wait for the **Alert support after** time (SET-05).

Expect
- Admin and support get an in-app message linking to **Dispatch**. The job shows under **Need attention**.

Check
- `dispatch_jobs.alertedAt` is set and `widenings` went up (the search area grew). `dispatch_outbox`: `DispatchAlert`.

## Support's dispatch page

### DLV-11 Watch, reassign and hand back

Who: support · Where: back office, **Dispatch**

Steps
1. Open **Dispatch**: the map shows **Riders online** and **Open deliveries**. Zoom out to the whole country: "Zoom in to follow riders live".
2. Open a searching job, click **Assign**, pick a free rider and give a reason.
3. Open an assigned job, click **Reassign** ("Take it off the current rider and search again").
4. Open a job and click **Hand back** with a reason.

Expect
- Step 2: "Assigned." The rider gets the job without an offer.
- Step 3: "Searching for a rider again."
- Step 4: the job goes to the store. The store's board shows "Handed back to the store" and lets them choose their own rider.

Check
- Step 2: `dispatch_outbox` `RiderAssigned`. Step 3: `RiderUnassigned`, then a new search.
- Step 4: `dispatch_jobs`: `mode: "SELF"`, `status: "WAITING"`, `closeReason` with the reason. `order_orders`: `deliveryMode: "SELF"`, `handedBackAt` set. `dispatch_outbox`: `DeliveryHandedBack`.

## Riders' pay

### DLV-12 Earnings

Who: rider · Where: rider pages, **Earnings**

Steps
1. Open **Earnings** after two deliveries, one with a tip.

Expect
- **Earned**, **Tips**, **Deliveries**, a chart of "Earnings per day", and **Latest deliveries** with each order.
- Each delivery pays the share of the normal delivery charge set in SET-05, never less than the minimum, even for a free-delivery coupon. Tips go 100 % to the rider.

Check
- `dispatch_jobs`: `payBasisMinor` (the normal charge, before any free delivery) and `earningMinor` = `payBasisMinor` × the share, at least the minimum.
- `rider_earnings`: the same `earningMinor`, and `tipMinor`.

### DLV-13 Admin sees a rider's numbers

Who: admin · Where: **Riders**, a rider

Expect
- **Offers accepted**, **Customer rating**, deliveries, earnings, last seen, papers, and **Suspend**.
- After **Suspend** the rider cannot go online and gets no offers.

Check
- `rider_riders.status: "SUSPENDED"`.

## Stores that deliver themselves

### DLV-14 The store picks its own rider at accept

Who: napoli, `napoli.rider@demo.foodstack.local` · Where: back office

Steps
1. As the store rider, go online.
2. Place a delivery order at Napoli Slice.
3. As napoli, **Accept** and choose the rider Usman Tariq under **Rider for order**, then **Assign**.
4. As the store rider: **I'm at the store**, **Picked up**, **Delivered…** with the code.

Expect
- No offer: the job goes straight to the chosen rider. The customer sees "Napoli Slice · DHA Phase 5 is delivering this order with their own rider."
- The store rider never gets platform jobs, and sees only their own branch's deliveries.

Check
- `dispatch_jobs`: `mode: "SELF"`, `status` `WAITING`, then `ASSIGNED`, …, `DELIVERED`. `riderKind: "STORE"`.
- `rider_riders` for Usman Tariq: `kind: "STORE"`, `storeId` = the branch.

### DLV-15 Accept now, choose the rider later

Who: napoli · Where: **Live orders**

Steps
1. Accept a delivery order with **Choose later**.
2. Later, open it and choose a rider.

Check
- Before step 2: `dispatch_jobs.status: "WAITING"`. After: `"ASSIGNED"`.

### DLV-16 The store delivers without a rider

Who: napoli · Where: **Live orders**

Steps
1. Accept a delivery order and do not choose a rider. Mark it **Out for delivery**, then **Delivered**.

Check
- `order_orders.status: "DELIVERED"`. `dispatch_jobs` is closed with `status: "DELIVERED"` by the store.

### DLV-17 A rider who stops sending location goes offline

Who: rider · Where: rider page

Steps
1. Go online, then close the browser (or go offline in F12) for 6 minutes.

Expect
- The rider is shown **Offline** on **Riders** and gets no offers.

Check
- `rider_riders.online: false`. Redis: `foodstack:rider:pos:<riderId>` has expired or the rider is not in any GEO set.
