# Run FoodStack with Docker Compose

Everything on one machine: the proxy (HTTPS), the FoodStack server with every module, the customer
web app, the back office, and MongoDB, Redis, Kafka and Elasticsearch. For a pilot city or a first
launch (up to about 200 orders an hour). To grow beyond one machine, see the
[migration guide](../../docs/migration-guide.md).

| File | What you do with it |
|---|---|
| `docker-compose.yml` | Nothing. It starts every part |
| `.env.example` | Copy to `.env` and fill in: your addresses, datastore passwords, map tiles |
| `config/application.yml` | Fill in: keys, buckets, maps, email, payments. Every FoodStack setting is here |
| `Caddyfile` | Nothing. The proxy: HTTPS and routing |
| `docker-compose.local.yml`, `config/application-local.yml`, `local/` | Only for a trial on your own computer |

Which module uses which setting and which outside service:
[architecture 1.0](../../docs/architecture/01-big-picture.md#10-what-each-service-needs).

## Try it on your own computer

No domain, buckets or map account needed. Photos and emails stay on your computer, maps use
OpenStreetMap's public servers, and card payments go to a test page.

```bash
cp .env.example .env            # nothing to change for a trial
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

Open http://localhost:8080 (customers) and http://localhost:8080/admin (sign in as
`admin@foodstack.localhost` / `foodstack-local`). Emails arrive at http://localhost:8025. The first
start takes a few minutes while the images download. Not for real data: the trial's keys are public.

Remove it, with its data: `docker compose -f docker-compose.yml -f docker-compose.local.yml down -v`.

## Install on a server

**You need:** a Linux server with 4 CPUs, 8 GB of memory and 100 GB of disk (8 CPUs, 16 GB for a busy
city), Docker Engine 27+ with Compose, ports 80 and 443 open, and your three names pointing at it
([handbook 10](../../docs/handbook/10-server.md), [11](../../docs/handbook/11-domain-and-dns.md)).
Before you start, set up the buckets ([handbook 12](../../docs/handbook/12-storage.md)) and the map
provider ([handbook 13](../../docs/handbook/13-maps.md)).

1. **Get the files** of the version you want (the release page has them as one zip):
   ```bash
   curl -LO https://github.com/ilyaslabs/foodstack/releases/download/v1.0.0/foodstack-compose-1.0.0.zip
   unzip foodstack-compose-1.0.0.zip && mv foodstack-compose-1.0.0 ~/foodstack && cd ~/foodstack
   cp .env.example .env && chmod 600 .env config/application.yml
   ```
2. **Make the keys and passwords**, once. Keep a copy of all of them in a password manager.
   ```bash
   openssl rand -hex 24          # run three times: MONGODB_PASSWORD, REDIS_PASSWORD, ELASTICSEARCH_PASSWORD in .env
   openssl rand -base64 32       # pii-key in config/application.yml
   openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 \
     | openssl pkcs8 -topk8 -nocrypt | sed '1d;$d' | tr -d '\n'; echo   # signing-keys in config/application.yml
   ```
   **Never lose the `pii-key`.** Without it, stored names, phones and addresses can't be read.
3. **Fill in `.env`** (addresses, those passwords, map tiles) and **`config/application.yml`**
   (keys, first administrator, buckets, maps, email, payments). Every line says what it is for.
   This shows what's still left:
   ```bash
   grep -n 'REPLACE\|example' .env config/application.yml
   ```
4. **Start:**
   ```bash
   docker compose up -d
   docker compose ps          # every part "healthy" after a few minutes
   ```
   Caddy gets the HTTPS certificates by itself once DNS points here. The server creates its
   databases on the first start, and checks the buckets on every start: if they aren't right it
   stops, and `docker compose logs server` says what to fix.
5. **Sign in** at `https://admin.example.com` with the administrator in `config/application.yml`.
   Set up the authenticator app it asks for, then change the password. Carry on with
   [handbook 17](../../docs/handbook/17-first-sign-in.md).

Docker starts everything again after a reboot (`restart: unless-stopped`), as long as Docker itself
starts at boot (`sudo systemctl enable docker`).

## Change a setting

Edit `config/application.yml` (or `.env`), then:

```bash
docker compose restart server      # a change in config/application.yml
docker compose up -d               # a change in .env
```

Branding, languages, currency, fees, commission and everything else about the business are set in
the back office, not in files.

## Day to day

| What | Command (in the folder with `docker-compose.yml`) |
|---|---|
| Status of every part | `docker compose ps` |
| Follow the server's log | `docker compose logs -f server` (or `proxy`, `mongodb`, `kafka`, …) |
| Stop everything (data kept) | `docker compose stop` |
| Start again | `docker compose up -d` |
| Restart the server | `docker compose restart server` |
| Free disk space | `df -h /` and `docker system df` |

## Back up

Only MongoDB holds data that can't be rebuilt: Redis is a cache, search is rebuilt from MongoDB,
and events waiting for Kafka are kept in MongoDB until sent. Photos are in your buckets: turn on
versioning there ([handbook 20](../../docs/handbook/20-backups.md)).

```bash
mkdir -p backups
docker compose exec -T mongodb sh -c 'mongodump --quiet --archive --gzip -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin' \
  > backups/foodstack-$(date -u +%Y%m%dT%H%M%SZ).archive.gz
```

Run it every night from cron, and copy `backups/` **off the server** (the exact cron line and the
copy to another place are in [handbook 20](../../docs/handbook/20-backups.md)). Keep `.env` and
`config/` in your password manager too: a backup without the `pii-key` can't be read.

## Restore

```bash
docker compose stop proxy web backoffice server
docker compose exec -T mongodb sh -c 'mongorestore --quiet --archive --gzip --drop -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --nsInclude "foodstack_*.*"' \
  < backups/<the backup>.archive.gz
# Redis and the search index only hold copies: clear them so nothing from after the backup survives
docker compose exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHALL'
docker compose exec -T elasticsearch sh -c 'es="curl -fsS -u elastic:$ELASTIC_PASSWORD http://localhost:9200"; for i in $($es/_cat/indices/foodstack_search_stores_v*?h=index); do $es/$i -X DELETE; done'
docker compose up -d
```

Store search fills again a few minutes after the server starts. **On a new server**, install first
with the old server's `pii-key` and `signing-keys` in `config/application.yml`, then restore.

## Upgrade

1. Read the release notes, and back up (above).
2. Get the new version's zip, and copy any new lines from its `.env.example` and
   `config/application.yml` into yours (the release notes list them). Then set the version:
   ```bash
   sed -i 's/^FOODSTACK_VERSION=.*/FOODSTACK_VERSION=1.1.0/' .env
   docker compose pull && docker compose up -d
   ```
   The server updates its databases as it starts. Customers see the site unavailable for about a minute.
3. **Going back**: set the old version in `.env` and `docker compose up -d` again. Database updates
   always work with the previous version, unless the release notes say otherwise.

## Before you go live

- [ ] Only ports 22, 80 and 443 open; SSH by key only.
- [ ] Automatic OS security updates.
- [ ] `.env` and `config/application.yml` readable only by you (`chmod 600`), and copied to a password manager.
- [ ] Nightly backups copied off the server, and one restore tried on a spare machine.
- [ ] Real email, maps and payment accounts in `config/application.yml`; `environment: production`.
- [ ] Two-step sign-in for every staff account that can change money or settings (required for administrators and finance).
- [ ] A disk alert at 80% full.

## If something goes wrong

| What you see | What to do |
|---|---|
| `server` keeps restarting | `docker compose logs server`: the last lines name the setting that is wrong |
| "Storage check failed: …" | A bucket setting in `config/application.yml` ([handbook 12](../../docs/handbook/12-storage.md#if-something-goes-wrong)) |
| "Could not resolve placeholder 'MONGODB_PASSWORD'" | `.env` is missing that line, or the server was started without it: `docker compose up -d` from this folder |
| "foodstack.security.pii-key must be at least 32 bytes" | `pii-key` still says REPLACE, or was cut short |
| The site says 503 for half a minute after a restart | The proxy waits for the server's health check; it clears itself |
| No HTTPS certificate | DNS doesn't point here yet, or port 80 is closed: `docker compose logs proxy` |

More: [handbook troubleshooting](../../docs/handbook/90-troubleshooting.md).
