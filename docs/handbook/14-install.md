# 14 Install FoodStack

[Back to the handbook](README.md)

FoodStack runs on your server with Docker Compose: one folder holds everything, and one command
starts the customer app, the back office, the database and the other services FoodStack needs,
with secure `https://` addresses. All the settings are in two files in that folder, `.env` and
`config/application.yml`, so you can always see and change them.

## What you need

- The server from [guide 10](10-server.md), signed in as your own user, with Docker.
- Your three names pointing at it ([guide 11](11-domain-and-dns.md)). Check with `nslookup` first: FoodStack needs them to get certificates.
- Your storage buckets and key ([guide 12](12-storage.md)) and your map provider ([guide 13](13-maps.md)), in your password manager.
- An email address for the first administrator account (yours).
- About 20 minutes.

## Steps

1. **Download the FoodStack release** onto the server. The releases page lists them:
   `https://github.com/ilyaslabs/foodstack/releases`. Copy the link of the file named
   `foodstack-compose-<version>.zip`, then:
   ```bash
   cd ~
   curl -LO <link to foodstack-compose-<version>.zip>
   unzip foodstack-compose-<version>.zip && mv foodstack-compose-<version> ~/foodstack && cd ~/foodstack
   cp .env.example .env && chmod 600 .env config/application.yml
   ```
   `~/foodstack` is where FoodStack lives from now on. Every other guide uses it.
2. **Make the keys and passwords**, once. Save each one in your password manager as you make it:
   ```bash
   openssl rand -hex 24          # run three times: MONGODB_PASSWORD, REDIS_PASSWORD, ELASTICSEARCH_PASSWORD
   openssl rand -base64 32       # the personal-data key (pii-key)
   openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 \
     | openssl pkcs8 -topk8 -nocrypt | sed '1d;$d' | tr -d '\n'; echo   # the signing key (signing-keys)
   ```
   **Never lose the personal-data key.** Without it, the names, phones and addresses FoodStack
   stores can't be read, and it cannot be made again.
3. **Fill in `.env`**: `nano .env`. Put in your three names (`FOODSTACK_SITES`, `FOODSTACK_HOSTNAMES`,
   `FOODSTACK_ADMIN_ROOT_HOST`), the three passwords from step 2, the map tiles
   ([guide 13](13-maps.md), step 7) and `MEDIA_ORIGINS` ([guide 12](12-storage.md), step 9). Save
   with Ctrl+O, Enter, and leave with Ctrl+X.
4. **Fill in `config/application.yml`**: `nano config/application.yml`. Every part says what it is for:
   - `security.issuer`: `https://` and your API name, such as `https://api.example.com`;
   - `security.pii-key` and `identity.signing-keys`: the two keys from step 2;
   - `identity.bootstrap-admin`: your email and a password of 12 characters or more, for the first sign-in;
   - `storage`: your buckets ([guide 12](12-storage.md), step 8);
   - `geo`: your map provider ([guide 13](13-maps.md));
   - `order.web-url` and `notification.admin-url`: your customer and back office addresses.

   Leave email and card payments for now ([guide 15](15-email.md), [guide 16](16-card-payments.md)).
   This shows what is still left to fill in:
   ```bash
   grep -n 'REPLACE\|example' .env config/application.yml
   ```
   Only the email lines (and anything you chose to leave) should remain.
5. **Start FoodStack:**
   ```bash
   docker compose up -d
   ```
   The first start takes a few minutes while Docker downloads everything. FoodStack creates its
   database, tests your buckets and gets its certificates by itself. If the buckets are not right,
   it stops and says what to fix (below).
6. **Make it start after a reboot**: `sudo systemctl enable docker`. Docker then starts FoodStack
   again by itself.
7. **Save a copy of both files** in your password manager, in a secure note named "FoodStack settings":
   ```bash
   cat .env config/application.yml
   ```
   They hold the keys that protect your customers' personal data.

## Check it worked

```bash
cd ~/foodstack
docker compose ps
```

Every line says `running` and `healthy` after a few minutes. Then, in your browser:

- `https://example.com/` shows the customer app asking for a location, with a padlock in the address bar.
- `https://admin.example.com/admin/` shows the sign-in page. Sign in with the administrator from step 4.
- After you sign in, a restaurant logo you add shows, and its address starts with your `public-base-url`.

Continue with [Email](15-email.md), then [First sign-in](17-first-sign-in.md).

## Undo

```bash
cd ~/foodstack
docker compose down -v
```

This removes FoodStack and **all its data on the server** (orders, users). Photos stay in your
buckets; empty them at your provider if you want them gone. The folder is kept, with your settings
and the `backups` folder; delete it yourself when you no longer need it: `rm -rf ~/foodstack`.
Without `-v`, `docker compose down` stops and removes the containers but keeps the data.

## Try it on your own computer first

If you want to look around before renting a server, run a trial on your laptop. It needs
[Docker Desktop](https://www.docker.com/products/docker-desktop/) (Mac or Windows) or Docker
(Linux), and no domain, buckets or map provider (photos stay on your computer and maps use
OpenStreetMap's public servers, which is fine for a look around). In the folder from step 1:

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

Then open `http://localhost:8080/admin/` and sign in as `admin@foodstack.localhost` with the
password `foodstack-local`. Emails arrive at `http://localhost:8025`. Remove it, with its data:
`docker compose -f docker-compose.yml -f docker-compose.local.yml down -v`.

## If something goes wrong

`docker compose logs server` shows the server's messages; the last lines name what is wrong.

| What you see | What to do |
|---|---|
| `server` keeps restarting, "Storage check failed: …" | The buckets are not ready. The message says what; [guide 12](12-storage.md#if-something-goes-wrong) lists each fix. Then `docker compose restart server` |
| "… needs foodstack.geo.google-api-key", "OpenStreetMap's public server allows light use only" | Fix the `geo` part of `config/application.yml` ([guide 13](13-maps.md)), then `docker compose restart server` |
| "foodstack.security.pii-key must be at least 32 bytes" | `pii-key` still says REPLACE, or was cut short when you pasted it |
| "Could not resolve placeholder 'MONGODB_PASSWORD'" | `.env` is missing that line, or you ran Docker from another folder. Run `docker compose up -d` in `~/foodstack` |
| "port is already allocated" for 80 or 443 | Another web server runs on this machine. Stop it (`sudo systemctl disable --now apache2` or `nginx`), or use a fresh server |
| "Cannot connect to the Docker daemon" | Go back to [guide 10](10-server.md), step 7 |
| The browser warns "Not secure" or shows a certificate error | The names did not point at the server when FoodStack started, so it could not get certificates. Check `nslookup`, then `docker compose restart proxy` and wait a minute ([guide 22](22-certificates.md)) |
| The site says 503 for half a minute after a restart | The proxy waits for the server to be ready; it clears itself |
