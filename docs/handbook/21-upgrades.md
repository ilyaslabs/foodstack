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
   cat /opt/foodstack/VERSION
   ```
2. Download the new version's `unit-deployer-<version>.zip` into your home folder (copy its link from the releases page):
   ```bash
   cd ~
   curl -LO <link to unit-deployer-<version>.zip>
   ```
3. Upgrade:
   ```bash
   cd /opt/foodstack
   ./upgrade.sh ~/unit-deployer-<version>.zip
   ```
   It backs up first (into `backups/`), keeps a copy of the current program in `.previous`, installs the new one, updates the database, and starts. It ends with `Upgraded to <version>`. Your settings (`.env`) and data are not touched.

## Steps (Kubernetes)

1. Read the release notes, then:
   ```bash
   helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version <version> \
     -n foodstack -f my-values.yaml
   ```
   The database is updated first by a short job; then the server pods are replaced a few at a time, so customers notice nothing.
2. Upgrade the dashboards the same way if you use them (`foodstack-observability`, same version).

## Check it worked

- `cat /opt/foodstack/VERSION` (or `helm -n foodstack list`) shows the new version.
- `./status.sh` lists every service as running and **healthy** (Kubernetes: `kubectl -n foodstack get pods`, all `Running`).
- Open the customer app and the back office, and look at **Live orders**: today's orders are there.

## Undo

**One server**:

```bash
cd /opt/foodstack
./upgrade.sh --rollback
```

This puts back the program from before the upgrade. It does **not** undo database changes: each version's database changes work with the version before it, so the old program runs on the new database. Only if the release notes say a version cannot be rolled back this way, restore the backup the upgrade made ([guide 20](20-backups.md)), which loses the orders since then.

A rollback can be done once: it goes back to the version before the **last** upgrade.

**Kubernetes**:

```bash
helm rollback foodstack -n foodstack
```

## If something goes wrong

| Problem | What to do |
|---|---|
| "is not a FoodStack unit deployer package" | The download is not the release zip (perhaps an error page). Download it again and check the file name |
| "unzip is required" | `sudo apt -y install unzip` |
| The upgrade stops at "Running migrations" with an error | Run `./upgrade.sh --rollback`, then send the error to the FoodStack team. If the old version does not start either, restore the backup the upgrade made ([guide 20](20-backups.md)) |
| A service is not healthy after the upgrade | `./logs.sh server` says why. Roll back, then report it |
| Kubernetes: the upgrade hangs on the migrations job | `kubectl -n foodstack logs -l app.kubernetes.io/name=foodstack-migrations` shows why. `helm rollback` returns to the old version |
