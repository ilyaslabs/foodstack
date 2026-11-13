# 99 Glossary

[Back to the handbook](README.md)

The technical words in this handbook, in plain terms.

| Word | Meaning |
|---|---|
| **Back office** | FoodStack's website for staff, restaurants and riders, at `https://admin.example.com/admin/` |
| **Backup** | A copy of your data, made with `mongodump` ([guide 20](20-backups.md)) |
| **Bucket** | A folder at a storage provider (Amazon S3, Cloudflare R2, Backblaze B2) that holds files. FoodStack uses two: a public one for photos and a private one ([guide 12](12-storage.md)) |
| **Branch** | One location of a restaurant. Every restaurant is a **brand** with one or more branches |
| **Broker** | One machine of Kafka |
| **CDN** | Content delivery network: servers around the world that keep copies of images and pages near customers ([runbook 49](49-cdn.md)) |
| **Certificate** | What makes the padlock in the browser: proves the site is yours and keeps the connection private ([guide 22](22-certificates.md)) |
| **Cluster** | Several machines working as one. A Kubernetes cluster runs FoodStack; a MongoDB, Redis or Elasticsearch cluster holds data |
| **Connection string** | The address and password of a database in one line, such as `mongodb+srv://user:password@host/` |
| **Container** | A packaged program that runs the same everywhere. Each FoodStack service is one container; Docker runs them |
| **Cron** | Linux's timer for jobs, such as the nightly backup (`crontab -e`) |
| **Deployment** | On Kubernetes, a group of identical copies (pods) of a program, such as `foodstack-core` |
| **CORS** | A rule on a bucket that lets web pages on your addresses send files to it. Without it, uploads from the browser fail ([guide 12](12-storage.md)) |
| **DNS** | The internet's address book: turns `example.com` into your server's IP address ([guide 11](11-domain-and-dns.md)) |
| **Docker** | The program that runs the containers on a single server |
| **`.env`** | One of the two settings files of a single-server install, `~/foodstack/.env`: your addresses, datastore passwords and map tiles. Keep a copy safe |
| **`application.yml`** | The other settings file, `~/foodstack/config/application.yml`: keys, buckets, maps, email and payments. Holds the personal-data key: keep a copy safe |
| **Docker Compose** | The tool that starts every part of FoodStack on one server from `docker-compose.yml` (`docker compose up -d`) |
| **Elasticsearch** | The search engine that finds restaurants near a customer. A copy built from the database |
| **Event** | A message from one part of FoodStack to the others, such as "order accepted". Sent through Kafka |
| **Failed event** | An event that could not be handled after several tries. Waits on the **Failed events** page to be replayed |
| **Gateway** | On Kubernetes, the front door that receives every request and sends it to the right Deployment. On one server, the `proxy` service (Caddy) does this |
| **Geocoding** | Turning a typed address into a place on the map, and back. Google or a Nominatim service does it ([guide 13](13-maps.md)) |
| **Helm** | The installer for Kubernetes. `helm upgrade` applies changes to `my-values.yaml` |
| **Index** | A sorted list a database keeps so it can find things fast. Also, in Elasticsearch, the collection of restaurants it searches |
| **Kafka** | The service that carries events between FoodStack's parts, in order, without losing them |
| **Kubernetes** | Software that runs programs on a cluster of machines, restarts them when they fail, and adds copies when busy |
| **Ledger** | FoodStack's record of every money movement: what each restaurant and rider earned and is owed |
| **Logs** | What a program writes about what it is doing. `docker compose logs server` shows them |
| **Map tiles** | The small square pictures a map is made of, loaded from a tile provider ([guide 13](13-maps.md)) |
| **Migration** | An update to the database's layout that a new version brings. Runs by itself during an upgrade |
| **Module** | One part of FoodStack, such as `order`, `store` or `dispatch`. All run together at first; they can be split ([runbook 42](42-split-modules.md)) |
| **MongoDB** | The database that holds everything that lasts: users, restaurants, orders, money |
| **mongos** | The router of a sharded MongoDB: programs connect to it, and it sends each query to the right shard |
| **`my-values.yaml`** | Your settings file for the Kubernetes install. Keep it safe, with the secrets out of it |
| **Partition** | One lane of a Kafka topic. FoodStack's topics have 24, so up to 24 copies can read at once |
| **Personal-data key** | `FOODSTACK_PII_KEY`: the key that locks customers' emails, phones and addresses in the database. Lose it and they cannot be read again |
| **Pod** | On Kubernetes, one running copy of a program |
| **Primary** | The member of a replica set that takes the writes |
| **Recovery codes** | Ten one-time codes for signing in without your phone ([guide 17](17-first-sign-in.md)) |
| **Redis** | A fast memory store for short-lived data: caches, limits, live updates, riders' positions |
| **Replica** | A copy: of a program (more copies share the load) or of data (more copies keep it safe) |
| **Replica set** | Three or more MongoDB machines holding the same data, so one can fail |
| **Restore** | Putting a backup back, with `mongorestore` ([guide 20](20-backups.md)) |
| **Rollback** | Going back to the version before an upgrade |
| **Secret** | On Kubernetes, a safe place for passwords and keys, such as `foodstack-secrets` |
| **Shard** | One part of a database split across machines; each shard is a replica set ([runbook 44](44-mongodb-sharding.md)) |
| **Shard key** | The field that decides which shard a record goes to, such as the order id |
| **Signing key** | `FOODSTACK_SIGNING_KEYS`: the key that proves a sign-in is real. A new one signs everyone out |
| **SMTP** | The way programs send email through an email service ([guide 15](15-email.md)) |
| **SSH** | The secure way to type commands on your server from your computer ([guide 10](10-server.md)) |
| **Topic** | A named stream of events in Kafka, one per FoodStack part, such as `foodstack.order.events` |
| **TTL** | Time to live: how long DNS answers are remembered. Lower it before moving a site |
| **Two-step sign-in** | A password plus a code from an authenticator app ([guide 17](17-first-sign-in.md)) |
| **Volume** | Where Docker keeps a service's data on the server, so it survives restarts |
| **Webhook** | An address another service (Stripe) calls to report something, such as a payment |
