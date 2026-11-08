# 11 Reviews

[Back to QA index](README.md)

Customers rate a finished order: the branch with 1 to 5 stars, an optional text and photo, and the rider with stars and tags. The branch can reply once. Admin and support moderate. Ratings feed the store page and the search ranking (SRCH-08).

Database: `foodstack_review`:

| Collection | What it holds |
|---|---|
| `review_orders` | `_id` = the order: what can be rated, written when the order is delivered or collected (`completedAt`, `riderId`, `riderKind`, `storeReviewId` once rated, `riderRated`) |
| `review_reviews` | One review of a branch: `orderId`, `orderNumber`, `storeId`, `brandId`, `customerName` (first name and initial), `rating`, `text`, `originalText` (only when words were masked), `photo`, `status` (`PUBLISHED` or `HIDDEN`), `flagged`, `hiddenReason`, `hiddenBy`, `hiddenAt`, `reply`, `createdAt` |
| `review_store_stats` | `_id` = the branch: `brandId`, `count`, `sum`, `stars` (`{"1": n, …, "5": n}`). Counts published reviews only |
| `review_rider_ratings` | `_id` = the order: `riderId`, `riderKind`, `storeId`, `customerId`, `rating`, `tags`, `createdAt` |
| `review_rider_stats` | `_id` = the rider: `count`, `sum`, `tags` (how often each tag was chosen) |

Events: `review_outbox`, Kafka `foodstack.review.events` (`ReviewPublished`, `ReviewHidden`).

### REV-01 Rate the store and the rider

Who: `customer@demo.foodstack.local` · Where: customer app, the order page of a delivered order with an operator rider (DLV-08)

Steps
1. On the order page, under **How was your order?**, give **Rate Spice Route · Gulberg** 4 stars and write `Tasty, came hot`.
2. Under **Rate your rider, …**, give 5 stars and pick **Friendly** and **On time**.
3. Click **Send rating**.
4. Open `/stores/spice-route-gulberg` and scroll to **Ratings and reviews**.

Expect
- Before sending: "You can rate until …" (14 days after delivery). **Send rating** with no stars says "Choose stars first."
- Step 3: "Thanks for your rating!" The page then shows **Your review** and "You rated … 5 out of 5."
- Step 4: the review with 4 stars, "Demo C." as the name, and the text. The average and the count ("1 review", "{n} reviews") went up, and the bar for 4 stars grew.

Check
- `review_orders`, `_id` = the order: `storeReviewId` set, `riderRated: true`.
- `review_reviews`: `rating: 4`, `text: "Tasty, came hot"`, `customerName: "Demo C."`, `status: "PUBLISHED"`, `flagged: false`, no `originalText`.
- `review_store_stats` for Gulberg: `count` +1, `sum` +4, `stars."4"` +1.
- `review_rider_ratings`: `rating: 5`, `tags: ["FRIENDLY", "ON_TIME"]`, `riderKind: "OPERATOR"`. `review_rider_stats` for the rider: `count` +1, `sum` +5, `tags.FRIENDLY` +1, `tags.ON_TIME` +1.
- `review_outbox`: `foodstack.review.ReviewPublished.v1`. Within seconds, the Elasticsearch document of the branch has new `ratingCount`, `ratingAverage`, `ratingScore` and `rankFactor`.

### REV-02 Stars only, rider only, and a photo

Who: customer · Where: order pages of three more finished orders

Steps
1. On one order, give the store stars only (no text) and send.
2. On another, rate only the rider.
3. On a third, add a photo with **Add a photo**, write a text and send.
4. On a pickup order, look for the rider rating.

Expect
1. The review shows with stars and no text.
2. Only the rider is rated. The store rating can still be given later on the same page.
3. "Uploading photo…", then the photo shows in the review on the store page. A file that is not an image says "The photo could not be uploaded. Try another one."
4. A pickup order has no rider to rate.

Check
- Step 2: no `review_reviews` document for that order, `review_orders.riderRated: true`, `storeReviewId` missing.
- Step 3: `review_reviews.photo` has `mediaId`, `thumbUrl` and `url`. The photo is in the media store (RustFS in the local run).

### REV-03 Ratings the server refuses

Who: tester · Where: the browser's console or curl, signed in as the customer

Steps
1. Rate an order that is still being prepared.
2. Rate the store of an order a second time.
3. Rate the rider of a pickup order.
4. Send a text of 1,001 characters.
5. Send a text without store stars.
6. Rate someone else's order.
7. Rate an order delivered more than 14 days ago (change `review_orders.completedAt` to 15 days ago to test).

Expect (all through `POST /api/v1/reviews`)
1. 409 `REVIEW_NOT_YET`: "You can rate an order once it is delivered or collected".
2. 409 `REVIEW_ALREADY_RATED`.
3. 400 `REVIEW_INVALID`, `errors.riderRating`: "No rider delivered this order".
4. 400 `REVIEW_INVALID`, `errors.text`: "Up to 1000 characters".
5. 400 `REVIEW_INVALID`, `errors.storeRating`: "Give the store a rating with your review".
6. 409 `REVIEW_NOT_YET`, the same as an order that is not finished, so nobody learns whether the order exists. Nothing is written.
7. 409 `REVIEW_WINDOW_CLOSED`: "Ratings for this order have closed". The order page no longer offers rating.
- An empty rating (no store and no rider stars): 400 `REVIEW_EMPTY`.

### REV-04 Blocked words are masked and sent for checking

Who: customer, then admin · Where: customer app, then back office **Reviews**

Steps
1. Rate an order with the text `Tasty but a bit crap packaging`.
2. Open the store page.
3. As admin, open **Reviews**, tab **To check**.

Expect
- Step 2: the review reads "Tasty but a bit **** packaging".
- Step 3: the review is listed with a **Blocked words** label and "As written: Tasty but a bit crap packaging".

Check
- `review_reviews`: `text: "Tasty but a bit **** packaging"`, `originalText: "Tasty but a bit crap packaging"`, `flagged: true`, `status: "PUBLISHED"`. A flagged review still counts in `review_store_stats`.
- The built-in list covers English, Arabic and Urdu, matching whole words only ("scrap" is not masked). Extra words come from the server setting `foodstack.review.blocked-words`.

### REV-05 Moderation: keep, hide and show again

Who: admin (or support) · Where: back office, **Reviews**

Steps
1. On the flagged review from REV-04, click **Keep shown**.
2. On another review, click **Hide**. In "Hide this review?", leave the reason empty and try, then enter `Not about this order` and confirm.
3. Open the **Hidden** tab and click **Show again**.
4. As spiceroute, open **Branches > Spice Route · Gulberg > Reviews**.

Expect
1. "Kept shown and taken out of the queue." The **To check** tab is empty: "Nothing to check. New flagged reviews show up here."
2. A reason is required. Then "Review hidden." The store page no longer shows it, and the average and count drop.
3. "Review shown again." It is back on the store page and counts again.
4. The branch sees its hidden reviews marked **Hidden**, and has no **Hide**, **Show again** or **Keep shown** buttons.

Check
- Step 1: `flagged: false`, `status` still `PUBLISHED`.
- Step 2: `status: "HIDDEN"`, `hiddenReason`, `hiddenBy` (the admin's id), `hiddenAt`. `review_store_stats`: `count` −1, `sum` − the stars, the star bar −1. `review_outbox`: `ReviewHidden`.
- Step 3: `status: "PUBLISHED"`, stats back up, `ReviewPublished` again.
- Hiding twice: 409 `REVIEW_ALREADY_HIDDEN`. Showing a shown one: 409 `REVIEW_ALREADY_SHOWN`.
- As spiceroute, `POST /api/v1/reviews/<id>/hide` answers 403 `REVIEW_ACCESS_DENIED`.

### REV-06 The branch replies

Who: spiceroute · Where: back office, **Branches > Spice Route · Gulberg > Reviews** (a branch manager uses **My store > Reviews**)

Steps
1. On a review, click **Reply**, write `Thanks, see you again!` and click **Post reply**.
2. Try to reply again to the same review.
3. Reply with a blocked word.
4. As napoli, try to reply to a Spice Route review (through the API).

Expect
1. "Customers see your reply under the review. You can reply once." After posting: "Reply posted.", and the review shows **Public reply**, and the store page shows "Reply from the store" under it.
2. No **Reply** button any more.
3. The word is masked in the reply.
4. 403 `REVIEW_ACCESS_DENIED`: "Only the branch can reply".

Check
- `review_reviews.reply`: `{text, by: <spiceroute's id>, at}`.
- A second reply: 409 `REVIEW_ALREADY_REPLIED`.

### REV-07 Brand-wide rating

Who: nobody · Where: customer app, a branch of a brand with more than one rated branch

Steps
1. Rate an order at Spice Route DHA Phase 5 as well as Gulberg.
2. Open either store page.

Expect
- The page shows the branch's own rating and "All Spice Route branches: … from … reviews".

Check
- `GET /api/v1/reviews/stores/<storeId>/summary` gives the branch's and the brand's count and average. The brand's numbers add up the `review_store_stats` of every branch with that `brandId`.

### REV-08 Rider ratings are for the operator

Who: admin, then spiceroute · Where: back office, **Riders > a rider**, then the API

Steps
1. As admin, open the rider rated in REV-01.
2. As spiceroute, call `GET /api/v1/reviews/riders/<riderId>`.

Expect
1. The rider's average, number of ratings and how often each tag was chosen (DLV-13).
2. 403 `REVIEW_ACCESS_DENIED`: "Rider ratings are for the operator".

### REV-09 A deleted customer's reviews stay

Who: customer, then anyone · Where: customer app

Steps
1. Delete the account of a customer who wrote a review with a photo (PRIV-03), and let the grace period pass.
2. Open the store page.

Expect
- The review is still there and still counts, under "Deleted user", without the photo.

Check
- `review_reviews`: `customerName: "Deleted user"`, no `photo`. `review_store_stats` unchanged.
