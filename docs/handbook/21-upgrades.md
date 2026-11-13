# 21 Upgrades

[Back to the handbook](README.md)

New FoodStack versions bring fixes and features. On one server an upgrade takes a backup, swaps the program, updates the database, and starts again, with about a minute offline. On Kubernetes there is no downtime.

## What you need

- The new version's **release notes**, from `https://github.com/ilyaslabs/foodstack/releases`. Read them first: they say if a version needs anything from you.
- A quiet time (early morning). Tell restaurants if you like; the site is offline for about a minute.
- About 15 minutes.

Upgrade one version at a time if you are several behind, unless the release notes say you may skip.

## Steps (one server)

1. Check what you run now:
   ```bash
   cd ~/foodstack
   grep FOODSTACK_VERSION .env
   ```
2. **Back up** ([guide 20](20-backups.md), step 1), and keep the files you are about to replace:
   ```bash
   mkdir -p previous && cp docker-compose.yml docker-compose.local.yml Caddyfile previous/
   ```
3. **Get the new version's files** (copy the link of `foodstack-compose-<version>.zip` from the releases page) and put its Compose and proxy files in place. Your `.env` and `config/` are not touched:
   ```bash
   cd ~
   curl -LO <link to foodstack-compose-<version>.zip>
   unzip -o foodstack-compose-<version>.zip
   cp foodstack-compose-<version>/docker-compose.yml foodstack-compose-<version>/docker-compose.local.yml \
      foodstack-compose-<version>/Caddyfile ~/foodstack/
   cp -r foodstack-compose-<version>/local ~/foodstack/
   ```
4. **Add new settings**, if the release notes list any. This shows what the new version's sample files have that yours don't:
   ```bash
   diff ~/foodstack/.env foodstack-compose-<version>/.env.example
   diff ~/foodstack/config/application.yml foodstack-compose-<version>/config/application.yml
   ```
   Lines starting `>` are in the new samples. Copy the new ones into your files, with your values; leave your own values as they are.
5. **Start the new version:**
   ```bash
   cd ~/foodstack
   sed -i 's/^FOODSTACK_VERSION=.*/FOODSTACK_VERSION=<version>/' .env
   docker compose pull && docker compose up -d
   ```
   The server updates its databases as it starts. Customers see the site unavailable for about a minute.

## Steps (Kubernetes)

1. Read the release notes, then:
   ```bash
   helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version <version> \
     -n foodstack -f my-values.yaml
   ```
   The database is updated first by a short job; then the server pods are replaced a few at a time, so customers notice nothing.
2. Upgrade the dashboards the same way if you use them (`foodstack-observability`, same version).

## Check it worked

- `grep FOODSTACK_VERSION ~/foodstack/.env` (or `helm -n foodstack list`) shows the new version.
- `docker compose ps` lists every service as running and **healthy** (Kubernetes: `kubectl -n foodstack get pods`, all `Running`).
- Open the customer app and the back office, and look at **Live orders**: today's orders are there.

## Undo

**One server**:

```bash
cd ~/foodstack
cp previous/* .
sed -i 's/^FOODSTACK_VERSION=.*/FOODSTACK_VERSION=<the old version>/' .env
docker compose up -d
```

This puts back the program from before the upgrade. It does **not** undo database changes: each version's database changes work with the version before it, so the old program runs on the new database. Only if the release notes say a version cannot be rolled back this way, restore the backup you made in step 2 ([guide 20](20-backups.md)), which loses the orders since then.

**Kubernetes**:

```bash
helm rollback foodstack -n foodstack
```

## If something goes wrong

| Problem | What to do |
|---|---|
| "End-of-central-directory signature not found" from `unzip` | The download is not the release zip (perhaps an error page). Download it again and check the file name |
| "unzip: command not found" | `sudo apt -y install unzip` |
| "manifest unknown" or "not found" from `docker compose pull` | The version in `.env` is mistyped: it is the number only, such as `1.1.0` |
| The server keeps restarting after the upgrade | `docker compose logs server` says why: often a new setting the release notes list (step 4). Fix it, or roll back (Undo), then report it |
| Kubernetes: the upgrade hangs on the migrations job | `kubectl -n foodstack logs -l app.kubernetes.io/name=foodstack-migrations` shows why. `helm rollback` returns to the old version |
