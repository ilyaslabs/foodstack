# 14 Operations

[Back to QA index](README.md)

Installing and running FoodStack the way an operator does: the Docker Compose install, health, failed events, rebuilding search, backups, upgrades, and the Kubernetes chart with its dashboards. Most cases here use **Docker Compose** (`deploy/compose`), not the local run. The [Operator handbook](../docs/handbook/README.md) has the full steps; these cases check that they work. Every command runs in the Compose folder: `deploy/compose` in the repository, or the folder unpacked from a release's `foodstack-compose-<version>.zip`. To test unreleased code, set `FOODSTACK_VERSION` in `.env` to a snapshot tag such as `main-snapshot`.

In the cases below, `trial` means `docker compose -f docker-compose.yml -f docker-compose.local.yml`.

### OPS-01 Start a trial with one command

Who: tester · Where: a computer with Docker (Linux, or macOS or Windows with Docker Desktop), no sudo

Steps
1. `cp .env.example .env`, then `trial up -d`.
2. Open `http://localhost:8080/admin/` and sign in as `admin@foodstack.localhost` with `foodstack-local`.
3. Open `http://localhost:8080/` and `http://localhost:8025/`.
4. Run `trial ps`.

Expect
- Step 1 needs no edits and no sudo; every container starts.
- Step 2: the back office opens and asks to set up the authenticator app (AUTH-20). Step 3: the customer app opens and asks for a location (no stores yet); Mailpit opens.
- Step 4: every service `Up` and `(healthy)`, `proxy` `Up`.

Check
- `docker volume ls` shows `foodstack_mongodb`, `foodstack_redis`, `foodstack_kafka`, `foodstack_elasticsearch`, `foodstack_caddy` and the trial's `foodstack_storage`.
- `docker compose exec mongodb ls -l /data/db/.replica-keyfile` is read-only for its owner (`-r--------`).
- No demo data: only the administrator exists in `identity_users`.

### OPS-02 Change a setting and relaunch

Who: tester · Where: the trial of OPS-01

Steps
1. In `config/application-local.yml`, add `logging.level.io.github.ilyaslabs: DEBUG`, then `trial restart server`.
2. In `.env`, change `JAVA_HEAP` to `1g`, then `trial up -d`.

Expect
- Step 1: the server is healthy again within a minute, and `docker compose logs server` now has `DEBUG` lines from `io.github.ilyaslabs`. Remove the line and restart: they stop.
- Step 2: only `server` is recreated; `docker compose exec server sh -c 'echo $JAVA_TOOL_OPTIONS'` shows `-Xmx1g`.

Check
- `docker compose exec server ls /config` lists `application.yml` and `application-local.yml`: the folder is mounted, not copied into the image.

### OPS-02b Install for real with your own buckets and map provider

Who: operator · Where: a server with the three names pointing at it (handbook guides 10 and 11), buckets made as in handbook guide 12 (do it once each with Amazon S3, Cloudflare R2 and Backblaze B2 before a release), and a map provider (guide 13: once with Google, once with a Nominatim-compatible provider)

Steps
1. Follow handbook guide 14: fill in `.env` and `config/application.yml`, then `docker compose up -d`.
2. Sign in to the back office, add a brand logo and a dish photo, and open a branch's **Delivery** tab.
3. In the customer app, search for a street, and get a delivery quote on a store page.

Expect
- Step 1: `grep -n 'REPLACE\|example' .env config/application.yml` shows only what you chose to leave; every service becomes healthy and the three addresses open with a padlock.
- Step 2: the photos upload and show; their address starts with `storage.public-base-url`. The map shows the provider's tiles and its credit.
- Step 3: suggestions appear, only in `geo.geocoding.country-codes`. With `routing.provider: google` the quote's distance follows the roads.

Check
- `docker compose ps` has no `storage` or `mailpit` service.
- `docker compose logs server` shows "Storage check passed: buckets … and …, images at …".
- In each bucket, no `_foodstack-check/` file is left behind.
- Browser F12, Console: no blocked image, upload or tile address in the back office.

### OPS-02c Wrong buckets and maps stop the server

Who: tester · Where: a server as in OPS-02b

Steps
1. In `config/application.yml`, one at a time, followed by `docker compose restart server`: `geo.geocoding.url: https://nominatim.openstreetmap.org`; `geo.geocoding.provider: google` with no `google-api-key`; a bucket name mistyped; a wrong `secret-key`; the public bucket not public; the CORS rule removed from the private bucket.
2. Put the right value back after each one.

Expect
1. The server keeps restarting, and `docker compose logs server` says, in order: "foodstack.geo.geocoding.url: OpenStreetMap's public server allows light use only …"; "foodstack.geo.geocoding.provider: google needs foodstack.geo.google-api-key …"; "Storage check failed: the bucket '…' does not exist …"; "… the access key may not write to the bucket '…' … Check foodstack.storage.access-key and secret-key …"; "… a test file in the public bucket is not readable by everyone at … (HTTP 403) …"; "… the bucket '…' does not allow uploads from https://admin.example.com (CORS …)".
2. After each fix the server is healthy again.

### OPS-03 Health checks

Who: tester · Where: terminal

Steps
1. `curl -s http://localhost:8080/actuator/health/readiness` and `…/actuator/health/liveness`.
2. `docker compose stop mongodb`, then ask readiness again, run `docker compose ps` and open the customer app.
3. `trial up -d` (or `docker compose up -d` on a real install).

Expect
1. `{"status":"UP"}` for both.
2. `docker compose ps` no longer lists mongodb as running. Readiness stays `UP`: the server is fine and reconnects by itself, so nothing restarts it. Pages that need data show an error.
3. Everything comes back healthy within a couple of minutes, with no data lost.

Check
- Only the two health checks are reachable from outside. `/actuator/prometheus` and the rest of `/actuator` are not routed by the proxy.

### OPS-04 Events wait while Kafka is down

Who: tester · Where: local run or the trial

Steps
1. Stop Kafka (`docker stop <kafka container>`, or `docker compose stop kafka`).
2. Place a cash order as a customer.
3. Start Kafka again and wait a minute.

Expect
- Step 2: the order is placed and shown to the customer. The store's board does not show it yet, and no email goes out.
- Step 3: the store's board shows it, and the emails arrive. Nothing is lost or doubled.

Check
- During step 2: `foodstack_order.order_outbox` has the new events with `status: "NEW"`.
- After step 3: they are `SENT` with `sentAt`, and each appears once on `foodstack.order.events`.

### OPS-05 Failed events: see, replay, dismiss

Who: admin · Where: terminal, then back office **Settings > Failed events**

Steps
1. Send an event no module can read to the ledger topic:
   ```bash
   docker exec -i <kafka container> /opt/kafka/bin/kafka-console-producer.sh \
     --bootstrap-server localhost:9092 --topic foodstack.ledger.events --property parse.key=true --property key.separator='|' <<< 'qa-key|{ not json qa-poison'
   ```
2. Wait about a minute (each consumer tries 3 times with a growing pause), then open **Failed events**.
3. Click **Show event** on one entry.
4. Click **Replay** on the notification entry and confirm "Replay this event?".
5. On the new entry that appears, click **Dismiss** and confirm.
6. Open the **Replayed** and **Dismissed** tabs.

Expect
- Step 2: one entry for each module that reads ledger events (notification and payment), with the module, the consumer, the time and the error message. The title says "Unreadable event".
- Step 3: the raw event `{ not json qa-poison`.
- Step 4: "Sent again". It fails again in the notification module only, so a new entry for notification appears; payment does not get it again.
- Step 5: "Dismissed".
- With nothing open: "No failed events. Everything was handled."

Check
- `foodstack_settings.settings_dead_letters`: one document per failure, `_id` = `<dlt topic>:<partition>:<offset>`, with `module: "ledger"`, `originalTopic`, `consumerGroup`, `key: "qa-key"`, `error`, `valueCipher` (the event, encrypted), `failedAt`, `status` `OPEN`, then `REPLAYED` or `DISMISSED` with `handledBy` and `handledAt`, and `expiresAt` 30 days later.
- Kafka: the event is on `foodstack.ledger.events.dlt`.
- Replaying an entry twice: 409. Without signing in, `GET /api/v1/settings/dead-letters` answers 401.

### OPS-06 Rebuild the search index

Who: admin · Where: terminal, then customer app

Steps
1. Note the `version` of Spice Route Gulberg's search document (SRCH-09).
2. As admin, call `POST /api/v1/search/reindex` (for example from the browser console of the back office).
3. Search in the customer app during and after the rebuild.
4. Restart the server.

Expect
- Step 2 answers 202 `{"status":"STARTED"}`. As a non-admin it answers 403.
- Step 3: search works the whole time, with the same results.
- Step 4: the index is rebuilt on start, and search works within a minute.

Check
- The alias `foodstack_search_stores` still points at one index: `curl -s -u elastic:… http://localhost:<port>/_alias/foodstack_search_stores`.
- Every live branch is in the index: the document count equals the number of live branches with a delivery area or pickup.

### OPS-07 Back up and restore

Who: tester · Where: the trial (OPS-01) or a real install (OPS-02b), with a store, an order and a dish photo

Steps
1. Make a backup with the command of handbook guide 20, step 1.
2. Change something you can see: rename the store, place another order.
3. Restore the backup with the commands of guide 20, step 5.
4. Sign in and look at the store and orders.

Expect
- Step 1: "Backup done: …/backups/<timestamp>" with `mongodb.archive.gz` and `config.tar.gz`. The folder is readable only by its owner.
- Step 3 runs without errors and ends with every container started; the Elasticsearch command prints `{"acknowledged":true}` for each search index.
- Step 4: everything is as it was at the backup: the old store name, the later order gone. Signing in still works, and customers' emails and phone numbers still read correctly. Search finds the store again within a few minutes (the index is recreated and filled from the restored data).

Check
- `config.tar.gz` holds `.env` and `config/`. Without the `config/application.yml` of the same install (its `pii-key`), restored personal data cannot be read: keep it in a password manager.
- Redis: a key you set before step 3 (`docker compose exec redis redis-cli -a … set qa:stale 1`) is gone after it.
- Elasticsearch: `_cat/indices/foodstack_search_stores_v*?h=index,creation.date.string` shows a creation time after step 3.
- The nightly cron line of guide 20, step 3, run by hand with `sh -c` (with `\%` written as `%`), writes a new folder and "Backup done" to `backups/backup.log`.

### OPS-07b Restore onto a new server, and a wrong key

Who: tester · Where: two Compose folders, one after the other (handbook guide 20, "Restore on a new server")

Steps
1. In folder A, make a backup and copy the backup folder somewhere else. Then `trial down -v`.
2. Unpack a fresh Compose folder B. Copy the backup into its `backups/`, and `tar -xzf backups/<ts>/config.tar.gz` there.
3. `trial up -d`, wait until healthy, then restore (guide 20, step 5).
4. Sign in with A's administrator password.
5. Put a new random `pii-key` in the config (`openssl rand -base64 32`) and `trial restart server`. Then put the right one back and restart again.

Expect
- Step 3 and 4: A's data is there and A's administrator signs in.
- Step 5: the server does not become healthy, and `docker compose logs server` says "The personal-data key (foodstack.security.pii-key, FOODSTACK_PII_KEY) does not open the accounts already stored". With the right key it is healthy again.

Check
- `identity_users` holds exactly one `ADMIN` per administrator of A: the wrong key did not make a new first administrator.

### OPS-08 Upgrade and roll back

Who: tester · Where: a real install or the trial, on an older version

Steps
1. Follow handbook guide 21 to a newer version (or from a release to `main-snapshot`).
2. Check the app. Then follow guide 21, **Undo**.

Expect
- Step 1: the server updates its databases as it starts; the site is down for about a minute. Data, sign-ins and settings are kept, and `.env` and `config/` are unchanged apart from the version and the new settings you added.
- Step 2: `grep FOODSTACK_VERSION .env` shows the previous version, and `docker compose ps` shows the old images running. The app works.

Check
- `previous/` holds the Compose files and Caddyfile of the version before.
- The migration logs in `foodstack_<module>.<module>_databasechangelog` have the new change sets.

### OPS-09 Production refuses unsafe settings

Who: tester · Where: a real install's `config/application.yml` (`environment: production`)

Steps
1. Set `foodstack.payment.gateway: sandbox` and run `docker compose restart server`.
2. Set it back to `none` and restart.

Expect
1. The server does not start. `docker compose logs server` shows "The sandbox payment gateway cannot run in production; set foodstack.payment.gateway (PAYMENT_GATEWAY) to stripe or none".
2. It starts again.

### OPS-10 Remove an install

Who: tester · Where: the trial

Steps
1. `trial down`.
2. `trial up -d`, then `trial down -v`.

Expect
1. Containers are gone; `docker volume ls` still lists the data volumes, and the next `up -d` has the same data.
2. Containers and data volumes are gone. The folder, with `.env`, `config/` and `backups/`, is kept.

### OPS-10b Separate services

Who: tester · Where: one machine with Docker, or several (deploy/services/README.md)

Steps
1. Start MongoDB (replica set), Redis, Kafka and Elasticsearch, fill in the 15 files of `deploy/services` and the Caddyfile, and start `identity`, the other 14 services, the two apps and the proxy as the README shows.
2. Onboard a store and place a cash order end to end (STORE-01, ORD-03).
3. Group `order`, `dispatch` and `search` on their own and the rest in one `core` service (README, "Group modules into fewer services"), and repeat step 2.

Expect
- Step 1: every service answers `{"status":"UP"}` on `/actuator/health/readiness`.
- Step 2 and 3 work as on one machine: the store is found in search, the order goes from placed to collected, and its emails show as sent in the **Email log**.

Check
- No service logs an `ERROR` during the journey.
- `foodstack_<module>` databases exist for every module except `search`.

### OPS-11 Kubernetes: a trial install with the chart

Who: tester · Where: a Kubernetes cluster with Gateway API and Istio ambient (deployment guide 4.1), for example k3s on one machine

Steps
1. `helm install foodstack k8s/helm/foodstack -n foodstack --create-namespace -f k8s/helm/foodstack/values-dev.yaml`.
2. Follow the printed notes: point `foodstack.localhost` and `admin.foodstack.localhost` at the gateway, open the back office, and sign in with the printed password. Set up the authenticator app when asked.
3. Onboard a store and place an order end to end (STORE-01, ORD-03).
4. `helm upgrade foodstack k8s/helm/foodstack -n foodstack --reuse-values --set topology=split`, then `--set topology=microservices`.

Expect
- Step 1: the migrations job runs first, then every pod is ready: `kubectl -n foodstack get pods`.
- Step 3 works as on a single server.
- Step 4: Deployments change one at a time, and the app keeps working during the change. No data moves.

Check
- `helm template` without `values-dev.yaml` and without `datastores.s3` or `maps` stops with a message naming the missing value (for example "datastores.s3.endpoint: your bucket provider's S3 endpoint (handbook guide 12)"), and with `maps.tiles.url` on `tile.openstreetmap.org` it refuses the public server.
- `kubectl -n foodstack get gateway,httproute,hpa` shows the routes and autoscalers.
- An API call without a token to a signed-in route answers 401 from the gateway; a forged token too.
- Every pod is in the mesh: `kubectl -n foodstack get pods -L istio.io/dataplane-mode` shows `ambient` for each FoodStack pod.

### OPS-12 Kubernetes: edge rate limits

Who: tester · Where: terminal, against the cluster of OPS-11

Steps
1. Send 40 sign-in attempts within a minute from one address: `for i in $(seq 40); do curl -s -o /dev/null -w '%{http_code}\n' -X POST http://foodstack.localhost/api/v1/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"qa$i@example.com\",\"password\":\"wrong-password\",\"client\":\"web\"}"; done | sort | uniq -c`

Expect
- The first 30 answer 401 (each uses a different email, so the per-account limit of AUTH-07 does not start). After 30 the answers are 429 with a `Retry-After` header, for a minute.

### OPS-13 Kubernetes: dashboards and alerts

Who: tester · Where: the cluster of OPS-11

Steps
1. `helm install foodstack-observability k8s/helm/foodstack-observability -n foodstack-observability --create-namespace`, then turn tracing on (deployment guide 4.9).
2. Open Grafana (`kubectl -n foodstack-observability port-forward svc/foodstack-grafana 3000:3000`) and the **FoodStack** folder.
3. Place a few orders. Then do OPS-05 on the cluster.
4. Scale the server to 0 for 6 minutes.

Expect
- Three dashboards: overview, orders and delivery, events. With no traffic, a panel says "No traffic" instead of an empty number.
- Step 3: the order funnel moves, and the failed events show on the events dashboard. In Prometheus, the alert `FoodStackDeadLetters` fires.
- Step 4: `FoodStackPodDown` fires.
- In Explore, Loki has the server's logs as JSON lines, and a line's trace id opens the trace in Tempo, from the request through Kafka to the consumer.

### OPS-14 Kubernetes: shard a database that already has data

Who: tester · Where: a cluster install (OPS-11) on a MongoDB replica set with a few stores and orders, and a sharded MongoDB to move to (handbook runbook 44)

Steps
1. Convert the replica set into a sharded cluster with two shards (provider console, or MongoDB's "convert a replica set to a sharded cluster").
2. Point `MONGODB_URI` at `mongos`, set `datastores.mongodb.sharding: true`, and `helm upgrade`.
3. Place an order and open past orders.

Expect
- The migrations job finishes; every server pod becomes ready.
- Past orders, stores and users are all there; the new order goes through.

Check
- The migrations job's log (or the first server pod's) has "Sharded foodstack_… on {…}" lines and no "Please create an index that starts with the proposed shard key".
- `db.getSiblingDB('config').collections.countDocuments({ _id: /^foodstack_/, unsplittable: { $ne: true } })` is above 50.
- `db.getSiblingDB('foodstack_order').order_orders.getIndexes()` includes the shard key's index (`{ _id: 'hashed' }`).
