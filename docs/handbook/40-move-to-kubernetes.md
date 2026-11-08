# 40 Move from one server to Kubernetes

[Back to the handbook](README.md)

The big step: from one machine running everything to a Kubernetes cluster with managed databases, where several copies of FoodStack share the load and a machine can fail without the site going down. Your data, accounts and settings move with you; customers and staff stay signed in to the same addresses. Plan for about 30 to 60 minutes offline at a quiet time.

This runbook is the one in the handbook that needs real Kubernetes knowledge. If nobody on your team has it, a managed Kubernetes service (Amazon EKS, Google GKE, Azure AKS, DigitalOcean, Hetzner and others) plus a contractor for the first day is a good plan. Everything after this runbook is a few settings at a time.

**One-way step**: after customers order on the new install, going back means moving the data back the same way.

## What you need

- A Kubernetes cluster (1.31 or newer, 3 machines or more) with the Gateway API, Istio in ambient mode, cert-manager and the Metrics Server installed: [deployment guide 4.1](../deployment-guide.md#41-cluster-prerequisites) has the exact commands. `kubectl` and `helm` on your computer, connected to it.
- The datastores, preferably as managed services ([deployment guide 4.2](../deployment-guide.md#42-datastores)):

  | Datastore | For example | You need |
  |---|---|---|
  | MongoDB 7.1+, a replica set of 3 | MongoDB Atlas | The connection string (`mongodb+srv://…`) |
  | Kafka, 3 brokers | Confluent Cloud, Amazon MSK, Aiven | The broker addresses |
  | Redis 7+ | Amazon ElastiCache, Redis Cloud, Upstash | The URL (`rediss://…`) |
  | Elasticsearch 9, 3 nodes | Elastic Cloud | The URL and the `elastic` password |
  | Object storage | Amazon S3, Cloudflare R2, Backblaze B2 | Two buckets (`foodstack-public`, readable by everyone; `foodstack-private`), an access key and secret |

- Your single server's `/opt/foodstack/.env` (from the server or your password manager).
- Docker on your computer, or on the old server, to run the copying tools.
- Half a day to prepare, then the maintenance window.

## Steps

### Prepare (no downtime)

1. **Keys.** From the old `.env`, copy `FOODSTACK_SIGNING_KEYS` and `FOODSTACK_PII_KEY` into a Secret, with three new random values. Same keys means customers stay signed in and their personal details stay readable.
   ```bash
   kubectl create namespace foodstack
   kubectl -n foodstack create secret generic foodstack-keys \
     --from-literal=FOODSTACK_SIGNING_KEYS='<value from .env>' \
     --from-literal=FOODSTACK_PII_KEY='<value from .env>' \
     --from-literal=FOODSTACK_INTERNAL_TOKEN="$(openssl rand -hex 24)" \
     --from-literal=FOODSTACK_SERVICE_CLIENT_SECRET="$(openssl rand -hex 24)" \
     --from-literal=FOODSTACK_ADMIN_PASSWORD="$(openssl rand -hex 12)"
   ```
2. **Your other settings.** Every `SMTP_`, `STRIPE_`, `FCM_`, `GEOCODING_` and `ROUTING_` line you added to the old `.env` goes into a second Secret, as in [deployment guide 4.4](../deployment-guide.md#44-values-for-a-real-install) (`foodstack-providers`).
3. **Values.** Write `my-values.yaml` as in [deployment guide 4.4](../deployment-guide.md#44-values-for-a-real-install), with:
   - `hosts`: exactly the three addresses you use now;
   - `secrets.existingSecret: foodstack-keys`;
   - your datastores, and under `datastores.s3` the bucket names and `publicEndpoint` (where browsers load images, for example `https://s3.eu-west-1.amazonaws.com`, or your CDN);
   - `server.envFromSecrets: [foodstack-providers]`;
   - the map tile settings under `web.env` and `backoffice.env` ([guide 14](14-maps.md)).

   Keep it in your password manager; you need it for every upgrade.
4. **Lower the DNS time.** At your DNS provider, set the time to live (TTL) of the three records to 5 minutes, a day ahead, so the switch in step 13 takes effect quickly.

### The maintenance window

5. On the old server, stop the apps (the databases keep running) and take a final backup:
   ```bash
   cd /opt/foodstack
   ./stop.sh proxy web backoffice server
   ./backup.sh
   ```
   Customers now see the site as unreachable. Note the backup folder's name.
6. **Copy the database** into the new MongoDB, from the old server (it has Docker):
   ```bash
   docker run --rm -v /opt/foodstack/backups/<date and time>:/backup mongo:8.2 \
     mongorestore --uri '<new MongoDB connection string>' --archive=/backup/mongodb.archive.gz --gzip \
     --nsInclude 'foodstack_*.*' --drop
   ```
   It ends with "… document(s) restored successfully".
7. **Copy the photos** from the old server's storage to your bucket. The old storage's keys are `S3_ACCESS_KEY` and `S3_SECRET_KEY` in the old `.env`:
   ```bash
   cd /opt/foodstack
   ./start.sh storage
   docker run --rm --network foodstack \
     -e RCLONE_CONFIG_OLD_TYPE=s3 -e RCLONE_CONFIG_OLD_PROVIDER=Other -e RCLONE_CONFIG_OLD_ENDPOINT=http://storage:9000 \
     -e RCLONE_CONFIG_OLD_ACCESS_KEY_ID='<old S3_ACCESS_KEY>' -e RCLONE_CONFIG_OLD_SECRET_ACCESS_KEY='<old S3_SECRET_KEY>' \
     -e RCLONE_CONFIG_NEW_TYPE=s3 -e RCLONE_CONFIG_NEW_PROVIDER=AWS -e RCLONE_CONFIG_NEW_REGION='<bucket region>' \
     -e RCLONE_CONFIG_NEW_ACCESS_KEY_ID='<new access key>' -e RCLONE_CONFIG_NEW_SECRET_ACCESS_KEY='<new secret>' \
     rclone/rclone:1.68.2 sh -c 'rclone copy -P old:foodstack-public new:foodstack-public && rclone copy -P old:foodstack-private new:foodstack-private'
   ```
   For a provider other than Amazon, set `RCLONE_CONFIG_NEW_PROVIDER` and add `-e RCLONE_CONFIG_NEW_ENDPOINT=<its S3 address>` ([rclone's S3 page](https://rclone.org/s3/) lists them).
8. **Point the stored image addresses at the bucket.** Restaurants' logos, dishes and past orders keep a copy of each image's address, which still names the old server. The FoodStack repository has a script for it, [`tools/migrate/rewrite-media-urls.js`](../../tools/migrate/rewrite-media-urls.js); download it next to you, then:
   ```bash
   docker run --rm -v "$PWD":/work -e OLD_BASE=https://admin.example.com/foodstack-public \
     -e NEW_BASE='<publicEndpoint from step 3>/foodstack-public' -e DRY_RUN=true \
     mongo:8.2 mongosh '<new MongoDB connection string>' --quiet --file /work/rewrite-media-urls.js
   ```
   It lists how many documents would change. Run it again without `-e DRY_RUN=true` to change them. `OLD_BASE` is the old `S3_PUBLIC_ENDPOINT` followed by `/foodstack-public`.
9. **Install FoodStack on the cluster**:
   ```bash
   helm install foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version <the version your server runs> \
     -n foodstack -f my-values.yaml --wait --timeout 15m
   ```
   Use the **same version** as the old server (`cat /opt/foodstack/VERSION`); upgrade after the move. The database updates find nothing to do, the server pods start, and store search fills itself from the database within a few minutes.
10. **Find the cluster's address**:
    ```bash
    kubectl -n foodstack get gateway foodstack -o jsonpath='{.status.addresses[0].value}'
    ```
11. **Try it before customers do.** On your computer, add the three names with that address to your hosts file (`/etc/hosts` on Mac and Linux, `C:\Windows\System32\drivers\etc\hosts` on Windows), for example `203.0.113.10 example.com admin.example.com api.example.com`. Then follow [Check it worked](#check-it-worked). Certificates may not be issued yet (they need DNS, step 13): accept the browser warning for this test only.
12. Remove those lines from your hosts file again.
13. **Switch DNS.** Change the three records to the cluster's address ([guide 11](11-domain-and-dns.md)); a hostname instead of an IP address goes in a CNAME record. Within the TTL, traffic moves; cert-manager gets the certificates in a few minutes ([guide 22](22-certificates.md)).

### Afterwards

14. Keep the old server **stopped but intact** for a week (`./stop.sh` stops the rest). Then take one last backup off it and cancel it.
15. Set the DNS TTL back to an hour or more.
16. Set up backups on the cluster ([guide 20](20-backups.md#kubernetes)) and the dashboards ([guide 23](23-health-and-dashboards.md)).

## Check it worked

- `kubectl -n foodstack get pods` lists every pod `Running` and ready, and `kubectl -n foodstack get certificate` shows `READY True` after the DNS switch.
- Sign in to the back office with your existing password and two-step code. Past orders, restaurants and staff are there.
- A restaurant's logo and dish photos show, and their address (right-click, open the image in a new tab) is your bucket or CDN, not the old server.
- The customer app finds restaurants near you (search can take a few minutes to fill).
- A test order goes through end to end ([guide 18](18-first-store.md), steps 12 to 16).
- **Email log** shows the emails of that test order as sent.

## Undo

- **Before the DNS switch**: nothing changed for customers. `./start.sh` on the old server brings it back as it was; delete the cluster install with `helm -n foodstack uninstall foodstack` if you want to try again later.
- **After the DNS switch, in the first hours**: put the DNS records back to the old server's address and `./start.sh` there. Orders placed on the cluster in between are **not** on the old server; take note of them from **Orders** first.
- **Later**: move back the same way in reverse (a database dump from the cluster into the server's `restore.sh`), with a maintenance window.

## If something goes wrong

| Problem | What to do |
|---|---|
| Pods stay in `CreateContainerConfigError` | A Secret from step 1 or 2 is missing or in another namespace: `kubectl -n foodstack get secret foodstack-keys foodstack-providers` |
| Pods keep restarting | `kubectl -n foodstack logs deploy/foodstack-core` says which setting is wrong, usually a datastore address or password |
| Every signed-in page says 401 "Jwt verification fails" | istiod is missing `PILOT_JWT_ENABLE_REMOTE_JWKS=envoy` ([deployment guide 4.1](../deployment-guide.md#41-cluster-prerequisites)). Restart istiod until it is fixed |
| Pods restart with "FOODSTACK_PII_KEY does not open the accounts already stored" | `FOODSTACK_PII_KEY` in `foodstack-keys` is not the old one. Fix the Secret and `kubectl -n foodstack rollout restart deploy` |
| Everyone is signed out | `FOODSTACK_SIGNING_KEYS` is not the old one. Not harmful: people sign in again. Fix the Secret if you want old sessions back |
| Photos are broken | Step 8 was skipped or `NEW_BASE` is wrong. The bucket `foodstack-public` must allow public reading |
| The restore in step 6 says "not authorized" | The connection string's user needs the `restore` role (in Atlas: **Database Access**, "Atlas admin" or "readWriteAnyDatabase" plus restore) |
| Store search is empty after an hour | `kubectl -n foodstack logs deploy/foodstack-core | grep -i search`. Elasticsearch must be reachable from the cluster |
