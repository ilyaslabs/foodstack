# Run FoodStack as separate services

FoodStack is one program made of 15 modules. On one server every module runs inside one process
(the [unit deployer](../unit-deployer/src/main/dist/README.md)). The same program can also run
each module, or a group of modules, as its own service on its own machines, without Kubernetes.
Only the settings change, never the code.

This folder has everything for that:

| File | What it is |
|---|---|
| `<module>.yml` (15 files) | One service's complete settings. Copy it, fill in your values, start the service |
| `Caddyfile` | The proxy in front: HTTPS, and each module's API paths to the service that runs it |

On Kubernetes you don't need this folder: the Helm chart writes the same settings for you
([Kubernetes guide](../../docs/kubernetes-guide.md)). To move an existing install, see the
[migration guide](../../docs/migration-guide.md).

## What each service needs

Every service needs **MongoDB, Redis and Kafka**, the same three for all of them, plus the four
shared secrets below. The table shows only what is different.

| Service | Its own database | Extra datastore | Outside services | Calls |
|---|---|---|---|---|
| `identity` | `foodstack_identity` | | Google and Apple sign-in (optional) | |
| `settings` | `foodstack_settings` | | | media |
| `media` | `foodstack_media` | | **S3-compatible buckets (required)** | |
| `geo` | `foodstack_geo` | | **Address search (required)**: Google or Nominatim-compatible. Road distances (optional): Google or OSRM | settings |
| `store` | `foodstack_store` | | | geo, identity, media, settings |
| `search` | none | **Elasticsearch** | | geo, review, store |
| `promotion` | `foodstack_promotion` | | | |
| `cart` | `foodstack_cart` | | | geo, payment, promotion, settings, store |
| `payment` | `foodstack_payment` | | Stripe (optional; cash needs nothing) | settings |
| `order` | `foodstack_order` | | | cart, identity, payment, promotion, settings |
| `rider` | `foodstack_rider` | | | geo, identity, media, settings |
| `dispatch` | `foodstack_dispatch` | | | geo, rider, settings |
| `notification` | `foodstack_notification` | | SMTP server for emails, Firebase for push (both optional) | identity, order, settings |
| `ledger` | `foodstack_ledger` | | | settings |
| `review` | `foodstack_review` | | | identity, media |

"Calls" means the service asks those services for answers while it works, on their internal port
8081. Those services must be reachable from it. Everything else between services goes through
Kafka events, so a service that is down for a minute only delays them.

The two web apps don't read these files. The customer web app needs the map tiles setting, and the
back office needs the map tiles and your bucket's address (see [The two apps](#the-two-apps)).

### The shared secrets

Make each one once, and use the same value in every file:

| Value in the files | Make it with | Used for |
|---|---|---|
| `REPLACE-with-the-internal-token` | `openssl rand -hex 24` | Services calling each other on port 8081 |
| `REPLACE-with-the-service-client-secret` | `openssl rand -hex 24` | Background work that calls another service |
| `REPLACE-with-the-personal-data-key` | `openssl rand -base64 32` | Encrypted names, phones and addresses. **Keep a copy**: without it they can't be read |
| `REPLACE-with-the-signing-key` (identity only) | see the comment in `identity.yml` | Signing sign-in tokens |

Moving from one server? Take `FOODSTACK_PII_KEY` and `FOODSTACK_SIGNING_KEYS` from its `.env`
instead of making new ones. Otherwise stored personal data can't be read, and everyone is signed out.

## Start it

1. **Datastores.** MongoDB (a replica set), Redis, Kafka and Elasticsearch, reachable from every
   machine that runs a service. Managed services are easiest
   ([handbook 40](../../docs/handbook/40-move-to-kubernetes.md#what-you-need) lists some).
2. **Fill in the files.** In each file you use, replace every `REPLACE-...` value, every
   `example.com` address and every `*.internal` address. A quick check:
   ```bash
   grep -n 'REPLACE\|example\.com\|\.internal' *.yml
   ```
   prints what's left. Put a file somewhere only its service can read it (`chmod 600`), because it
   holds secrets. You can also write `${MY_VARIABLE}` in place of a value and pass the value as an
   environment variable.
3. **Start `identity` first.** Every other service checks sign-in tokens with its keys:
   ```bash
   docker run -d --name identity --restart unless-stopped -p 8080:8080 -p 8081:8081 \
     -v $PWD/identity.yml:/app/config/application.yml:ro \
     ilyasdotdev/ilyaslabs-foodstack:server-<version>
   ```
   Use the same `<version>` for every service. Each service creates and updates its own database
   on the first start.
4. **Start the others**, in any order, the same way with their own file. A service whose neighbours
   aren't up yet still starts, and its calls to them fail until they are.
5. **The proxy.** Put the `*.internal` addresses of your services into `Caddyfile`, then start it
   on the machine your domain points to (the command is at the top of the file). Caddy gets the
   HTTPS certificates by itself.
6. **The two apps** (next section).
7. Sign in to the back office at `https://admin.example.com` with the email and password from
   `identity.yml`, then carry on with [handbook 17](../../docs/handbook/17-first-sign-in.md).

Each service is healthy when `curl -s localhost:8080/actuator/health/readiness` answers `{"status":"UP"}`.

To run without Docker, use Java 25 and the server jar: `foodstack-server-<version>-exec.jar` from
Maven Central (`io.github.ilyaslabs:foodstack-server`, classifier `exec`), or
`app/foodstack-server.jar` in the release's unit-deployer zip:

```bash
java -jar foodstack-server.jar --spring.config.additional-location=file:./order.yml
```

### The two apps

```bash
docker run -d --name web --restart unless-stopped -p 4000:4000 \
  -e FOODSTACK_API_URL= \
  -e FOODSTACK_INTERNAL_API_URL=http://proxy.internal:8000 \
  -e FOODSTACK_ALLOWED_HOSTS=example.com,admin.example.com,api.example.com \
  -e MAP_TILE_URL='https://tiles.example-provider.com/{z}/{x}/{y}.png?key=...' \
  -e MAP_ATTRIBUTION='© Example provider © OpenStreetMap contributors' \
  ilyasdotdev/ilyaslabs-foodstack:web-<version>

docker run -d --name backoffice --restart unless-stopped -p 8080:8080 \
  -e FOODSTACK_API_URL= \
  -e MAP_TILE_URL='https://tiles.example-provider.com/{z}/{x}/{y}.png?key=...' \
  -e MAP_TILE_ORIGIN=https://tiles.example-provider.com \
  -e MAP_ATTRIBUTION='© Example provider © OpenStreetMap contributors' \
  -e MEDIA_ORIGINS='https://foodstack-public.s3.eu-west-1.amazonaws.com https://s3.eu-west-1.amazonaws.com' \
  ilyasdotdev/ilyaslabs-foodstack:backoffice-<version>
```

- `FOODSTACK_INTERNAL_API_URL` is where the web app gets data for the pages it renders: the
  proxy's internal port 8000.
- `MEDIA_ORIGINS` lists the addresses the back office may load photos from and upload to: your
  bucket's public address and its S3 endpoint.

## Group modules into fewer services

You don't need 15 services. A common shape is four: `order`, `dispatch` and `search` on their own,
and one `core` service for the other twelve. To build a group, start from one module's file and:

1. set every module of the group to `{ mode: local }` under `module-switch.modules`;
2. list them all under `module-switch.expose.modules`;
3. add the settings blocks of those modules' files (for example `foodstack.storage` from
   `media.yml`, `foodstack.geo` from `geo.yml`, `spring.mail` from `notification.yml`);
4. in every **other** file, and in `Caddyfile`, point those modules' addresses at the group.

If the group contains `identity`, also take `foodstack.identity` from `identity.yml` and leave out
`jwk-set-uri` and `token-uri`. If it doesn't, keep them.

## Several copies of a service

Run the same file on more machines, put a load balancer (or several addresses after
`reverse_proxy` in `Caddyfile`) in front of them, and point the other services' `url` for that
module at the load balancer. Copies share the work by themselves through Kafka and Redis.
