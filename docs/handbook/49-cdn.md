# 49 Put a CDN in front

[Back to the handbook](README.md)

A CDN (content delivery network, such as Cloudflare, Amazon CloudFront, Fastly or Bunny) keeps copies of your images and busiest pages on servers near your customers, so they load faster and your own servers do less. Two separate uses, either or both. Images work for one server and for Kubernetes; the API cache is for Kubernetes.

| Use | What is cached | Effect |
|---|---|---|
| **Images** | Logos, cover photos and dish photos | Photos load fast everywhere; the biggest share of bytes leaves your servers |
| **API** | Restaurant pages and menus, for up to 30 seconds | Busy restaurant pages are answered by the CDN; everything personal (carts, orders, sign-in) always goes to FoodStack |

FoodStack already marks what may be cached: restaurant pages and menus say `Cache-Control: public, s-maxage=30`; everything else says `no-store` or `private`. A CDN that follows those headers needs no special rules.

## What you need

- A CDN account, and access to your DNS ([guide 11](11-domain-and-dns.md)).
- 30 minutes.

## Steps

### Images

1. In the CDN, create a site (a "distribution" or "pull zone") whose **origin** is your public bucket, the `S3_PUBLIC_BASE_URL` in `/opt/foodstack/.env` (Kubernetes: `datastores.s3.publicBaseUrl`). Many storage providers offer this in their own console (Cloudflare R2 with a custom domain is already a CDN; Amazon S3 pairs with CloudFront).

   Give it an address of your own, such as `img.example.com` (a CNAME record at your DNS provider, as the CDN shows).
2. Test it: take a dish photo's address in the customer app (right-click the photo, copy image address), replace the `S3_PUBLIC_BASE_URL` part at the start with `https://img.example.com`, and open it. The photo shows.
3. Tell FoodStack to hand out the CDN's addresses:
   - one server: in `/opt/foodstack/.env`, set `S3_PUBLIC_BASE_URL=https://img.example.com` and add ` https://img.example.com` at the end of the `MEDIA_ORIGINS` value (inside the quotes), then `./restart.sh server` and `./restart.sh backoffice`;
   - Kubernetes: set `datastores.s3.publicBaseUrl: https://img.example.com` in `my-values.yaml`, then `helm upgrade`.

   On start the server reads a test file through the new address; if the CDN is not right yet it refuses to start, so step 2 must work first.
4. Point the addresses already stored (restaurants' logos, dishes, past orders) at the CDN, with the repository's script [`tools/migrate/rewrite-media-urls.js`](../../tools/migrate/rewrite-media-urls.js). `OLD_BASE` is the old `S3_PUBLIC_BASE_URL`. Try first with `DRY_RUN=true`. On one server:
   ```bash
   cd /opt/foodstack
   curl -LO https://raw.githubusercontent.com/ilyaslabs/foodstack/v$(cat VERSION)/tools/migrate/rewrite-media-urls.js
   docker compose cp rewrite-media-urls.js mongodb:/tmp/
   docker compose exec -e OLD_BASE='<old S3_PUBLIC_BASE_URL>' -e NEW_BASE=https://img.example.com -e DRY_RUN=true \
     mongodb sh -c 'mongosh -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --quiet --file /tmp/rewrite-media-urls.js'
   ```
   Then run the last command again without `-e DRY_RUN=true`. On Kubernetes, run the script with `mongosh` against your MongoDB connection string from any machine with Docker:
   ```bash
   docker run --rm -v "$PWD":/work -e OLD_BASE='<old publicBaseUrl>' -e NEW_BASE=https://img.example.com -e DRY_RUN=true \
     mongo:8.2 mongosh '<MongoDB connection string>' --quiet --file /work/rewrite-media-urls.js
   ```

### API (optional, Kubernetes)

5. In the CDN, create a second site for `api.example.com` with the cluster's gateway address as origin ([runbook 40](40-move-to-kubernetes.md), step 8), and set it to **respect the origin's cache headers**, to pass every header and cookie through, and to cache nothing without `Cache-Control: public`. Change the `api` DNS record to the CDN as it explains. On Cloudflare, that is the orange cloud on for `api` only, with SSL mode **Full (strict)**; keep `example.com` and `admin` as **DNS only**.
6. The gateway now sees the CDN's addresses instead of the customers'. Its per-address limits need the real one, which the CDN passes in `X-Forwarded-For`: set `gateway.trustedProxies: 1` (2 if a cloud load balancer also sits in front) in `my-values.yaml` and `helm upgrade`.

On one server, keep the API without a CDN: the front door would see one address, the CDN's, for every customer, and its settings file is replaced on each upgrade.

## Check it worked

- Images: a dish photo's address in the customer app starts with `https://img.example.com`, and the response headers (browser F12, **Network**, click the photo) show the CDN (for example `cf-cache-status: HIT` or `x-cache: Hit`) after the second load.
- API: a restaurant page's API request shows a cache hit header on the second load within 30 seconds; a cart or order request never does.
- Customers can still sign in and place an order.

## Undo

- Images: put back the old `S3_PUBLIC_BASE_URL` (Kubernetes: `publicBaseUrl`) and restart (or upgrade), then run step 4 with the two addresses swapped. Keep the CDN site until that is done.
- API: point the `api` record back at the gateway (Cloudflare: grey cloud), and set `gateway.trustedProxies` back.

## If something goes wrong

| Problem | What to do |
|---|---|
| Photos broken after step 3, or the server says "Storage check failed: … not readable by everyone at https://img.example.com/…" | The CDN's origin is wrong: step 2 must work first. Undo step 3 meanwhile |
| Customers see someone else's data | Stop: the CDN caches what it must not. Turn the API site off at once, and set it to respect origin headers only. FoodStack never marks personal answers as public |
| Sign-in "Too many tries" for many people at once | The limits see the CDN's address for everyone (step 6) |
| Cloudflare: "Too many redirects" | SSL mode is Flexible; set it to **Full (strict)** |
