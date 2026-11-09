# 14 Operations

[Back to QA index](README.md)

Installing and running FoodStack the way an operator does: the one-command install, health, failed events, rebuilding search, backups, upgrades, and the Kubernetes chart with its dashboards. Most cases here use a **real install** (`install.sh`), not the local run. The [Operator handbook](../docs/handbook/README.md) has the full steps; these cases check that they work.

Get the unit deployer zip from a release, or build it: `mvn -pl deploy/unit-deployer -am package -DskipTests` (it lands in `deploy/unit-deployer/target/`).

### OPS-01 Install a trial with one command

Who: tester · Where: a computer with Docker (Linux, or macOS or Windows with Docker Desktop), no sudo

Steps
1. Unzip the unit deployer and run `./install.sh ~/foodstack --local`.
2. Open `http://foodstack.localhost:8080/admin/` and sign in as `admin@foodstack.localhost` with `foodstack-local`.
3. Open `http://foodstack.localhost:8080/`.
4. Run `~/foodstack/status.sh`.

Expect
- Step 1 runs to the end without asking anything and without sudo, and ends with "FoodStack … is running." and the addresses and sign-in.
- Step 2: the back office opens. Step 3: the customer app opens and asks for a location (no stores yet).
- Step 4: every service `running` and `healthy`, and "Disk used: …%".

Check
- `~/foodstack/.env` exists with permission `-rw-------` (600) and holds generated passwords and keys. `FOODSTACK_ENVIRONMENT=production`, `PAYMENT_GATEWAY=none`.
- `~/foodstack/config/mongodb-keyfile` is read-only for its owner (400).
- `docker volume ls` shows `foodstack_mongodb`, `foodstack_storage` (the trial's local photo store) and the other data volumes. `.env` has `FOODSTACK_TRIAL_STORAGE=true`.
- No demo data: only the administrator exists in `identity_users`.

### OPS-02 The installer refuses what would break things

Who: tester · Where: the same computer as OPS-01

Steps
1. Run the same `install.sh ~/foodstack --local` again.
2. Run it into another folder, `~/foodstack2`.
3. With something listening on port 8080, run an install into a new folder after uninstalling (OPS-10).
4. Stop an install part way (Ctrl+C while it pulls images), then run the same command again.

Expect
1. "~/foodstack already holds an installation. Use upgrade.sh to update it, or uninstall.sh to remove it."
2. "FoodStack is already installed on this machine in …. Run …/uninstall.sh first."
3. "Port 8080 is already in use. Pick another with --http-port / --https-port."
4. "Removing an unfinished earlier install", then it finishes normally.

### OPS-02b Install for real with your own buckets and map provider

Who: operator · Where: a server with the three names pointing at it (handbook guides 10 and 11), buckets made as in handbook guide 12 (do it once each with Amazon S3, Cloudflare R2 and Backblaze B2 before a release), and a map provider (guide 13: once with Google, once with a Nominatim-compatible provider)

Steps
1. Write `~/foodstack-settings.env` with the `S3_*`, `GEOCODING_*` (and `GOOGLE_MAPS_API_KEY`), `MAP_*` and `ROUTING_*` lines, then run the install of handbook guide 14 with `--settings ~/foodstack-settings.env`.
2. Run it again on a fresh server with the file missing two lines (for example `S3_SECRET_KEY` and `MAP_ATTRIBUTION`), from a terminal.
3. Sign in to the back office, add a brand logo and a dish photo, and open a branch's **Delivery** tab.
4. In the customer app, search for a street, and get a delivery quote on a store page.

Expect
- Step 1 asks nothing and ends with "FoodStack … is running.".
- Step 2 asks for the two missing values (the secret is not shown as you type), then installs.
- Step 3: the photos upload and show; their address starts with `S3_PUBLIC_BASE_URL`. The map shows the provider's tiles and its credit.
- Step 4: suggestions appear, only in `GEOCODING_COUNTRY_CODES`. With `ROUTING_PROVIDER=google` the quote's distance follows the roads.

Check
- `docker volume ls` has no `foodstack_storage`; `docker compose ps` has no `storage` service. `.env` has the S3 and map lines, quoted, and a `MEDIA_ORIGINS` line.
- `./logs.sh server` shows "Storage check passed: buckets … and …, images at …".
- In each bucket, no `_foodstack-check/` file is left behind.
- Browser F12, Console: no blocked image, upload or tile address in the back office.

### OPS-02c Missing or wrong buckets and maps stop the install

Who: tester · Where: a server as in OPS-02b, or any computer with Docker for steps 1 to 3 (they stop before anything starts)

Steps
1. `./install.sh ~/fs --domain example.com` with no settings file, input not from a terminal (`< /dev/null`).
2. A settings file with `MAP_TILE_URL=https://tile.openstreetmap.org/{z}/{x}/{y}.png`; then one with `GEOCODING_PROVIDER=google` and no key; then one with `S3_ENDPOINT=http://…`.
3. A settings file with `MAP_TILE_URL` without `{z}`.
4. On a server as in OPS-02b, install once each with: a bucket name mistyped; a wrong secret key; the public bucket not public; the CORS rule removed from the private bucket.

Expect
1. "Missing settings: S3_ENDPOINT … A real install needs your storage buckets and map provider: see handbook guides 12 and 13, then pass --settings <file>." Nothing is created.
2. "OpenStreetMap's public servers allow only light use and are for --local trials. …"; "Missing settings: GOOGLE_MAPS_API_KEY …"; "http://… must start with https://".
3. "MAP_TILE_URL must contain {z}, {x} and {y}."
4. The install stops with "Storage check failed: …" and what to fix, in order: "the bucket '…' does not exist …"; "the access key may not write to the bucket '…' … Check S3_ACCESS_KEY and S3_SECRET_KEY …"; "a test file in the public bucket is not readable by everyone at … (HTTP 403) …"; "the bucket '…' does not allow uploads from https://admin.example.com (CORS …)". After the fix, the same command installs.

### OPS-03 Health checks

Who: tester · Where: terminal

Steps
1. `curl -s http://foodstack.localhost:8080/actuator/health/readiness` and `…/actuator/health/liveness`.
2. `./stop.sh mongodb`, then ask readiness again, run `./status.sh` and open the customer app.
3. `./start.sh`.

Expect
1. `{"status":"UP"}` for both.
2. `status.sh` shows mongodb stopped. Readiness stays `UP`: the server is fine and reconnects by itself, so nothing restarts it. Pages that need data show an error.
3. Everything comes back healthy within a couple of minutes, with no data lost.

Check
- Only the two health checks are reachable from outside. `/actuator/prometheus` and the rest of `/actuator` are not routed by the proxy.

### OPS-04 Events wait while Kafka is down

Who: tester · Where: local run or a trial install

Steps
1. Stop Kafka (`docker stop <kafka container>`, or `./stop.sh kafka`).
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

Who: tester · Where: a trial install (OPS-01) with a store, an order and a dish photo. On a real install (OPS-02b) the same, without `storage.tar.gz`: photos are in the buckets

Steps
1. `./backup.sh`.
2. Change something you can see: delete the dish photo, rename the store, place another order.
3. `./restore.sh backups/<timestamp>` with the folder from step 1.
4. Sign in and look at the store and orders.

Expect
- Step 1: "Backup done: …/backups/<timestamp>" with `mongodb.archive.gz`, `storage.tar.gz` and `config.tar.gz`. The folder is readable only by its owner.
- Step 3: "Restore done." after stopping and starting the application.
- Step 4: everything is as it was at the backup: the old store name, the photo back, the later order gone. Signing in still works, and customers' emails and phone numbers still read correctly.
- Step 3 prints "Clearing caches and the search index" before "Starting". Search finds the store again within a few minutes (the index is recreated and filled from the restored data).

Check
- `config.tar.gz` holds `.env`, `config/` and `VERSION`. Without the `.env` from the same install (its `FOODSTACK_PII_KEY`), restored personal data cannot be read: keep it in a password manager.
- Redis: a key you set before step 3 (`redis-cli -a … set qa:stale 1`) is gone after it.
- Elasticsearch: `_cat/indices/foodstack_search_stores_v*?h=index,creation.date.string` shows a creation time after step 3.

### OPS-07b Restore onto a new server, and a wrong key

Who: tester · Where: two trial installs, one after the other (handbook guide 20, "Restore on a new server")

Steps
1. On install A, `./backup.sh` and copy the backup folder somewhere else. `./uninstall.sh --yes`.
2. Install B in a new folder. Copy the backup into its `backups/`. Copy **only** `FOODSTACK_PII_KEY` and `FOODSTACK_SIGNING_KEYS` from A's `.env` (`tar -xzf backups/<ts>/config.tar.gz -O .env`) into B's `.env`.
3. `./restart.sh server`, then `./restore.sh backups/<ts>`.
4. Sign in with A's administrator password.
5. Put a new random `FOODSTACK_PII_KEY` in `.env` (`openssl rand -base64 32`) and `./restart.sh server`. Then put the right one back and restart again.

Expect
- Step 3 and 4: A's data is there and A's administrator signs in.
- Step 5: the server does not become healthy, and `./logs.sh server` says "FOODSTACK_PII_KEY does not open the accounts already stored". With the right key it is healthy again.

Check
- `identity_users` holds exactly one `ADMIN` per administrator of A: the wrong key did not make a new first administrator.

### OPS-08 Upgrade and roll back

Who: tester · Where: a trial install

Steps
1. Build or download a newer unit deployer zip and run `./upgrade.sh <zip>`.
2. Check the app. Then `./upgrade.sh --rollback`.

Expect
- Step 1: it backs up first, installs the new version, runs migrations, and ends with "Upgraded to …". The site is down for about a minute.
- Data, sign-ins and settings are kept.
- Step 2: "Rolled back." and `cat VERSION` shows the previous version. The app works.
- Without a zip: "usage: upgrade.sh <unit-deployer.zip> | --rollback". With a wrong zip: "… is not a FoodStack unit deployer package".

Check
- `backups/` has a new folder from the upgrade. `.previous/` holds the files of the version before.
- The migration logs in `foodstack_<module>.<module>_databasechangelog` have the new change sets.

### OPS-08b Upgrade an install that kept photos on the server

Who: tester · Where: an install made with a release from before buckets were required, with a dish photo

Steps
1. `./upgrade.sh <new zip>`.
2. Follow handbook guide 12, "Move photos from this server", to buckets of your own.

Expect
- Step 1 warns "Photos are still kept on this server" and points to guide 12; the site keeps working with the photos where they were (`.env` gains `FOODSTACK_TRIAL_STORAGE=true`).
- Step 2: the photo shows from the bucket's address; `docker volume ls` no longer has `foodstack_storage`, and backups no longer have `storage.tar.gz`.

### OPS-09 Production refuses unsafe settings

Who: tester · Where: a trial install's `.env`

Steps
1. Set `PAYMENT_GATEWAY=sandbox` and run `./restart.sh server`.
2. Set it back to `none` and restart.

Expect
1. The server does not start. `./logs.sh server` shows "The sandbox payment gateway cannot run in production; set PAYMENT_GATEWAY to stripe or none".
2. It starts again.

### OPS-10 Uninstall

Who: tester · Where: a trial install

Steps
1. `./uninstall.sh`, then type anything but `delete`.
2. `./uninstall.sh` and type `delete`.

Expect
1. "Cancelled." Nothing is removed.
2. Containers and data volumes are gone. `backups/` is kept, with the message "Uninstalled. Delete … yourself once you no longer need its backups."

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

### OPS-11b Kubernetes playground: every topology on one computer

Who: tester · Where: a computer with Docker (8 GB of memory for it), the AWS CLI, kubectl, helm, Java 25 and Maven; no cloud account

Steps
1. Follow playground/k8s/README.md step 1: start Floci, create the EKS cluster with `aws eks create-cluster`, `aws eks update-kubeconfig`, create the ECR repository and the two buckets.
2. `playground/k8s/up.sh --registry 000000000000.dkr.ecr.us-east-1.localhost:4566/foodstack` (on Linux add `--push-to localhost:4566/000000000000/us-east-1/foodstack`).
3. Open the back office at the printed address and sign in with the printed password. Onboard a store with a photo (STORE-01) and place an order (ORD-03) in the customer app.
4. Run step 2 again with `--topology split --skip-build`, then `--topology microservices --skip-build`. Place another order after each.
5. `playground/k8s/down.sh`.

Expect
- Step 2 ends with the three addresses and the administrator's password. `kubectl -n foodstack get pods` shows only FoodStack's pods and the gateway's; `docker ps` shows the `fsplay-*` datastores.
- `aws ecr list-images --repository-name foodstack` lists the server-, web- and backoffice-playground tags, and the pods pulled them from there.
- Step 3: the photo shows (it loads from `localhost:4566`), and the order's emails are at http://localhost:8025.
- Step 4: the Deployments change to the new topology; the store and the first order are still there.
- Step 5: the FoodStack release and the `fsplay-*` containers are gone; the cluster and Floci stay.

Check
- The scripts never call Floci or the AWS CLI: `grep -rnw aws playground/k8s/*.sh playground/k8s/datastores` finds nothing.
- Without `maps.allowPublicOpenStreetMap: true`, `helm template` with the playground values refuses OpenStreetMap's public servers (OPS-11 Check).

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
