# 7. Delivery flows

[Back to index](README.md)

How a delivery order moves through `order`, `dispatch`, `rider`, `geo` and `notification` after M3, traced from the code. Solid arrows are synchronous calls (through a module's `-api` interface, or HTTP from a browser); dashed arrows are Kafka events or Redis messages. Who delivers is a store setting: `OPERATOR` (the operator's riders, found by dispatch) or `SELF` (the store's own riders, or the store with nobody chosen).

## 7.1 The delivery job's life

`dispatch` keeps one job per delivery order (`dispatch_jobs`, `_id` = order id) and a small copy of every unfinished job (`dispatch_active`) for the matcher and the dispatch board. Every change is one conditional update of the job's `status` and `revision` plus its event, in one MongoDB transaction (`DispatchService.change`).

```mermaid
stateDiagram-v2
    [*] --> SEARCHING: OrderAccepted, operator riders
    [*] --> WAITING: OrderAccepted, store delivers
    SEARCHING --> OFFERED: matcher offers the nearest free rider
    OFFERED --> SEARCHING: rider declines or the offer lapses
    OFFERED --> ASSIGNED: rider accepts
    SEARCHING --> ASSIGNED: support assigns a rider
    WAITING --> ASSIGNED: store picks one of its riders
    ASSIGNED --> AT_STORE: rider taps arrived
    ASSIGNED --> PICKED_UP: rider taps picked up
    AT_STORE --> PICKED_UP: rider taps picked up
    ASSIGNED --> SEARCHING: rider gives it up before pickup
    AT_STORE --> SEARCHING: rider gives it up before pickup
    SEARCHING --> WAITING: support hands it back to the store
    OFFERED --> WAITING: support hands it back
    ASSIGNED --> WAITING: support hands it back
    WAITING --> PICKED_UP: store marks it picked up
    PICKED_UP --> DELIVERED: code, photo or support
    WAITING --> DELIVERED: store marks it delivered
    SEARCHING --> CANCELLED: order cancelled
    WAITING --> CANCELLED: order cancelled
    ASSIGNED --> CANCELLED: order cancelled
    PICKED_UP --> CANCELLED: order cancelled
    DELIVERED --> [*]
    CANCELLED --> [*]
```

`HANDED_BACK` is not a status of its own: a handed-back job becomes a `WAITING` self-delivery job and keeps the reason in `closeReason`. A store rider's job given up before pickup goes back to `WAITING`, not `SEARCHING`. A cancelled order closes the job from any open status (`OFFERED` and `AT_STORE` too; the diagram shows a few). Support's reassign first takes the job off the old rider (`RiderUnassigned`), then assigns from `SEARCHING` or `WAITING`.

Events on `foodstack.dispatch.events`: `RiderOffered`, `RiderAssigned`, `RiderUnassigned`, `DeliveryPickedUp`, `DeliveryCompleted`, `DeliveryCancelled`, `DeliveryHandedBack` and `DispatchAlert`. Each carries the same delivery summary (order id and number, store, mode, rider), so a reader never calls back.

## 7.2 Store accepts, dispatch finds a rider

The delivery code is made when the order is placed (4 digits, only when the total is at least the operator's **Ask for the code above** amount). The order keeps the code encrypted with the PII key (`deliveryCodeCipher`). `OrderAccepted` carries what dispatch needs (`OrderDelivery`: pickup, drop-off, customer, cash to collect, the encrypted code); dispatch decrypts it once and keeps only a hash. See 7.10 for the code in the confirmation email.

```mermaid
sequenceDiagram
    autonumber
    participant St as Store (back office)
    participant Oc as order
    participant K as Kafka
    participant Dc as dispatch
    participant Rc as rider
    participant R as Redis
    participant Nc as notification
    participant F as FCM

    St->>Oc: POST /api/v1/orders/{id}/actions/accept (prepMinutes)
    Oc->>Oc: CONFIRMED to ACCEPTED, readyBy = now + prep
    Oc-->>K: OrderAccepted with OrderDelivery
    K-->>Dc: dispatch-orders listener (inbox)
    Dc->>Dc: insert dispatch_jobs and dispatch_active (SEARCHING, searchFrom = readyBy minus lead time)
    loop every 2 s on every replica, over the work buckets it holds (bucket = branch)
        Dc->>Dc: expire lapsed offers back to SEARCHING
        Dc->>Rc: RiderApi.findAvailable(store point, radius, 5, tried riders)
        Rc->>R: GEOSEARCH rider:geo:{cell} for each cell the circle touches,<br/>then rider:pos:{id} for a fresh ping
        Rc-->>Dc: free, active, online riders with distance, idle time, acceptance
        Dc->>Dc: rank by distance less idle bonus plus decline penalty
        Dc->>R: SET NX dispatch:offer:{riderId} (offer timeout + 5 s)
        Dc->>Dc: SEARCHING to OFFERED (offerRiderId, offerExpiresAt, earning)
        Dc-->>K: RiderOffered
        Dc-->>R: publish dispatch:rt:rider:{riderId} and dispatch:rt:cell:{pickup cell}
    end
    K-->>Nc: notification-dispatch listener (inbox)
    Nc->>F: push "New delivery" to the rider's devices
    K-->>Rc: rider listener counts the offer
```

When nobody free is in range, the job stays `SEARCHING`: after each 45-second round with no rider the radius grows by half (at most twice), riders who declined may be tried again after 3 minutes, and once the job has searched for **Alert support after**, one `DispatchAlert` goes out and `notification` tells admin and support users (in-app and push, link `/dispatch`).

## 7.3 The rider accepts

```mermaid
sequenceDiagram
    autonumber
    participant Rd as Rider app
    participant Dc as dispatch
    participant Rc as rider
    participant K as Kafka
    participant Oc as order
    participant Nc as notification
    participant Cu as Customer app

    Rd->>Dc: POST /api/v1/dispatch/rider/offers/{orderId}/accept
    Dc->>Dc: check the offer is still this rider's and not lapsed
    Dc->>Rc: RiderApi.getRider (name, vehicle)
    Dc->>Dc: OFFERED to ASSIGNED, rider on the job
    Dc-->>K: RiderAssigned
    Dc-->>Rd: the job (pickup, drop-off, cash, needsCode, earning)
    K-->>Rc: rider listener: rider_assignments row, rider busy (out of the GEO set)
    K-->>Oc: order listener: order.rider = name and vehicle
    Oc-->>Cu: live message on the order stream, page reloads
    K-->>Nc: rider-assigned to the customer (in-app and push), rider-job-assigned to the rider unless they accepted it themselves
```

An operator rider holds one job at a time, so a busy rider is removed from the GEO set of their cell until the job ends.

## 7.4 Live position and tracking

The rider app sends its position every 10 seconds while online (and a queued batch after being offline). The customer and the store follow one delivery; the operator's dispatch map follows the riders and jobs in the area it shows.

```mermaid
sequenceDiagram
    autonumber
    participant Rd as Rider app
    participant Rc as rider
    participant R as Redis
    participant M as MongoDB (foodstack_rider)
    participant Cu as Customer order page
    participant Gc as geo

    Rd->>Rc: POST /api/v1/rider/me/locations (up to 120 points)
    Rc->>R: rate limit 30 a minute
    Rc->>R: rider:pos:{id} (last point, 10 min expiry), GEOADD rider:geo:{cell} when free<br/>(ZREM from the old cell's set after a move, rider:cell:{id})
    Rc-->>R: publish rider:rt:cell:{cell} and rider:rt:order:{orderId} for each active order
    opt first ping in the sample window, or a batch
        Rc->>M: rider_riders lat, lng, lastSeenAt and rider_locations samples (expire after retention days)
    end
    Cu->>Rc: GET /api/v1/riders/tracking/{orderId}
    Rc->>Gc: GeoApi.route (rider to store, or rider to home after pickup)
    Rc-->>Cu: rider, position, etaSeconds, remainingMeters
    Cu->>Rc: GET /api/v1/riders/tracking/{orderId}/stream (SSE)
    R-->>Rc: rider:rt:order:{orderId}
    Rc-->>Cu: new position, map moves
```

**The operator's live map at country scale.** The map sends its view (south, west, north, east) and subscribes to the live channels of the 0.25° cells it shows, at most 64 (an area of about 220 × 220 km at most); a wider view gets the board without live dots and the hint "Zoom in to follow riders live". Moving the map re-subscribes after 600 ms. On Redis Cluster the channels use sharded pub/sub, so each message reaches only the node that owns its channel.

`geo` answers routes with OSRM when `ROUTING_PROVIDER=osrm`, otherwise with the straight-line distance times a road factor at an urban average speed. The customer sees the arrival time only after pickup; before that the card says the rider is heading to the restaurant. Riders that stop pinging for 5 minutes are set offline by `rider-stale-sweep` (every minute).

## 7.5 Pickup and handover

```mermaid
sequenceDiagram
    autonumber
    participant Rd as Rider app
    participant Dc as dispatch
    participant K as Kafka
    participant Oc as order
    participant Rc as rider
    participant Cu as Customer app

    Rd->>Dc: POST /api/v1/deliveries/{orderId}/arrived
    Dc->>Dc: ASSIGNED to AT_STORE
    Rd->>Dc: POST /api/v1/deliveries/{orderId}/picked-up
    Dc->>Dc: to PICKED_UP
    Dc-->>K: DeliveryPickedUp
    K-->>Oc: order to PICKED_UP (a rider's pickup also counts as ready)
    Oc-->>Cu: "On the way", arrival time shows
    Rd->>Dc: POST /api/v1/deliveries/{orderId}/delivered (code, cash, photo)
    Dc->>Dc: rate limit 5 tries in 10 min, compare the code hash
    alt code wrong or missing
        Dc-->>Rd: 400 DELIVERY_CODE_WRONG or DELIVERY_CODE_REQUIRED
    else code right
        Dc->>Dc: to DELIVERED (proof CODE, PHOTO, NONE or SUPPORT, cash collected)
        Dc-->>K: DeliveryCompleted with charge, earning and tip
        K-->>Oc: order to DELIVERED
        K-->>Rc: rider_earnings row, rider free again (back in the GEO set)
        Dc-->>Rd: done, earnings card refreshes
    end
```

The photo is uploaded first through `media` (purpose `RIDER_DOCUMENT`) and sent as `media:<id>`. Support may finish a job without the code (proof `SUPPORT`). The rider's earning is a share of the normal delivery charge (`riderPayBasisMinor`, before a free-delivery threshold or delivery coupon), with the minimum pay as a floor, so free deliveries pay the rider the same.

## 7.6 Support: reassign and hand back

```mermaid
sequenceDiagram
    autonumber
    participant Su as Support (Dispatch page)
    participant Dc as dispatch
    participant Rc as rider
    participant K as Kafka
    participant Oc as order
    participant St as Store board

    Su->>Dc: GET /api/v1/dispatch/jobs, SSE /api/v1/dispatch/live/stream
    Su->>Rc: GET /api/v1/riders/live, SSE /api/v1/riders/live/stream
    alt assign or reassign (reason required)
        Su->>Dc: POST /api/v1/dispatch/jobs/{orderId}/reassign (riderId, reason)
        Dc-->>K: RiderUnassigned for the old rider, RiderAssigned for the new one
    else hand back to the store
        Su->>Dc: POST /api/v1/dispatch/jobs/{orderId}/hand-back (reason)
        Dc->>Dc: mode SELF, status WAITING, rider and offer cleared
        Dc-->>K: DeliveryHandedBack
        K-->>Oc: order deliveryMode SELF, handedBackAt, note in the timeline
        Oc-->>St: board shows "Handed back to you" and "Choose rider"
    end
```

## 7.7 A store that delivers itself

Store riders belong to one branch (`rider_riders.kind = STORE`, `storeId`). They never get offers; the store chooses.

```mermaid
sequenceDiagram
    autonumber
    participant St as Store (back office)
    participant Rc as rider
    participant Oc as order
    participant K as Kafka
    participant Dc as dispatch

    St->>Rc: GET /api/v1/riders/stores/{storeId}
    St->>Oc: POST /api/v1/orders/{id}/actions/accept (prepMinutes, storeRiderId)
    Oc-->>K: OrderAccepted with storeRiderId
    K-->>Dc: job WAITING, then assign that store rider
    Dc-->>K: RiderAssigned
    opt no rider chosen at accept
        St->>Dc: POST /api/v1/deliveries/{orderId}/store-rider (riderId)
    end
```

Without any rider the store marks the order picked up and delivered on its board as before (M2), and `dispatch` closes the job from `OrderPickedUp` and `OrderDelivered`.

## 7.8 Push devices

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser (either app)
    participant Nw as notification-web
    participant Nc as notification
    participant F as FCM

    B->>Nw: GET /api/v1/devices/push-config (public)
    Nw-->>B: enabled, Firebase web config, VAPID key
    B->>B: register sw.js, ask permission, getToken
    B->>Nw: POST /api/v1/devices (token, platform, app)
    Nw->>Nc: PushService.register: _id = SHA-256 of the token, token encrypted, at most 10 devices a user
    Note over Nc,F: on a message with push on
    Nc->>F: HTTP v1 send with a service-account access token (5 s timeouts)
    F-->>Nc: 404 or UNREGISTERED removes the device
    B->>Nw: POST /api/v1/devices/remove on sign-out
```

Push is on only when `FCM_SERVICE_ACCOUNT` is set; the apps hide the "Turn on notifications" banner otherwise. In-app messages are always kept, so nothing depends on push arriving.

## 7.9 Saved addresses

Customers keep up to 20 addresses (`identity_addresses`, one `HOME`, one `WORK`, any number of named `OTHER`), each with a map pin and notes for the rider, through `GET`, `POST`, `PUT` and `DELETE` on `/api/v1/me/addresses`. The web app starts with the preferred one, checkout picks one, and a typed address is saved after the first order. The address on an order is a copy, so editing or deleting a saved address never changes past orders.

## 7.10 The delivery code in the confirmation email

```mermaid
sequenceDiagram
    autonumber
    participant Oc as order
    participant K as Kafka
    participant Nc as notification
    participant Em as Email

    Oc->>Oc: place order, make the code if the total needs one
    Oc->>Oc: encrypt the code with the PII key (deliveryCodeCipher)
    Oc-->>K: OrderConfirmed with the encrypted code
    K-->>Nc: OrderMessages.onOrderEvent
    alt delivery order with a code
        Nc->>Nc: decrypt, fill codeLine in the customer's language
    else pickup or no code
        Nc->>Nc: codeLine is empty
    end
    Nc->>Em: order-confirmed email with codeLine
```

The code is in clear only in memory while the email is written; the email itself is the customer's copy (PRD DR-6).

## 7.11 Rider papers

```mermaid
sequenceDiagram
    autonumber
    participant Bo as Back office (rider page)
    participant Me as media
    participant Rw as RiderPapersController
    participant Ro as RiderOnboarding (rider-core)
    participant Mo as MongoDB rider_onboarding

    Bo->>Me: upload front and back photos (purpose RIDER_DOCUMENT, private)
    Me-->>Bo: media ids
    Bo->>Rw: POST /api/v1/riders/{id}/papers/documents (type, number, photos, expiry)
    Rw->>Ro: addDocument (needs rider:manage, operator riders only)
    Ro->>Me: claim each photo for rider doc-(document id), with a service token
    Ro->>Mo: save, number encrypted, last 4 kept for display
    Ro-->>Bo: papers with short-lived photo links
    Bo->>Rw: POST .../documents/{docId}/verify
    Rw->>Ro: verifyDocument (who and when)
    Bo->>Rw: PUT /api/v1/riders/{id}/papers/payout (method, provider, holder, account)
    Rw->>Ro: setPayout, account encrypted
    Ro-->>Bo: complete when a checked, unexpired ID card or passport and payout are set
    Bo->>Rw: DELETE .../documents/{docId}
    Ro->>Me: release the photos
```

The riders list asks `GET /api/v1/riders/papers/complete` and marks other operator riders **Papers missing**. A save that raced another edit answers 409 `RIDER_PAPERS_CHANGED`.

