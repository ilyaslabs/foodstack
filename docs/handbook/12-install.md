# 12 Install FoodStack

[Back to the handbook](README.md)

One command installs everything on your server: the customer app, the back office, the database and the other services FoodStack needs, with secure `https://` addresses.

## What you need

- The server from [guide 10](10-server.md), signed in as your own user.
- Your three names pointing at it ([guide 11](11-domain-and-dns.md)). Check with `nslookup` first: the installer needs them to get certificates.
- An email address for the first administrator account (yours).
- A password manager open, to save what the installer prints.
- About 15 minutes.

## Steps

1. **Download the latest FoodStack release** onto the server. The releases page lists them: `https://github.com/ilyaslabs/foodstack/releases`. Copy the link of the file named `unit-deployer-<version>.zip`, then:
   ```bash
   cd ~
   curl -LO <link to unit-deployer-<version>.zip>
   unzip unit-deployer-<version>.zip
   cd unit-deployer-<version>
   ```
2. **Run the installer**, with your names and email:
   ```bash
   sudo ./install.sh /opt/foodstack \
     --domain example.com \
     --admin-domain admin.example.com \
     --api-domain api.example.com \
     --admin-email you@example.com \
     --systemd
   ```
   - `/opt/foodstack` is where FoodStack lives: its settings, logs and backups. Keep this folder; every other guide uses it.
   - `--systemd` makes FoodStack start again by itself when the server restarts.
   - It checks the server first, then works for a few minutes (downloading takes most of it). It asks you nothing.
3. **Save what it prints at the end**, in your password manager:
   ```
   FoodStack 1.0.0 is running.

     Customer app : https://example.com/
     Back office  : https://admin.example.com/admin/
     Sign in as   : you@example.com
     Password     : <generated password>   (shown once; also in /opt/foodstack/.env as FOODSTACK_ADMIN_PASSWORD)
   ```
4. **Save a copy of the settings file** in your password manager too. It holds the keys that protect your customers' personal data, and **it cannot be recreated**:
   ```bash
   sudo cat /opt/foodstack/.env
   ```
   Copy everything it shows into a secure note named "FoodStack .env".
5. **Let your user run the FoodStack scripts** without `sudo` (the folder belongs to root because of step 2):
   ```bash
   sudo chown -R $USER /opt/foodstack
   ```

## Check it worked

```bash
cd /opt/foodstack
./status.sh
```

Every line says `running` and `healthy`, and the last line shows how full the disk is.

Then, in your browser:

- `https://example.com/` shows the customer app asking for a location, with a padlock in the address bar.
- `https://admin.example.com/admin/` shows the sign-in page.

Continue with [Email](13-email.md), then [First sign-in](16-first-sign-in.md).

## Undo

```bash
cd /opt/foodstack
./uninstall.sh
```

Type `delete` when asked. This removes FoodStack and **all its data** (orders, users, photos). The `backups` folder is kept; delete it yourself when you no longer need it: `sudo rm -rf /opt/foodstack`.

## Try it on your own computer first

If you want to look around before renting a server, install a trial on your laptop. It needs [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Mac or Windows) or Docker (Linux), and no domain:

```bash
./install.sh ~/foodstack --local
```

Then open `http://foodstack.localhost:8080/admin/` and sign in as `admin@foodstack.localhost` with the password `foodstack-local`. Emails are not sent in a trial. Remove it with `~/foodstack/uninstall.sh`.

## If something goes wrong

| Message | What to do |
|---|---|
| "Port 80 is already in use" | Another web server runs on this machine. Stop it (`sudo systemctl disable --now apache2` or `nginx`), or use a fresh server |
| "Docker is not installed" or "Cannot reach Docker" | Go back to [guide 10](10-server.md), step 7 |
| "… already holds an installation" | FoodStack is already installed there. Use [Upgrades](21-upgrades.md) to update it |
| It stops part way (for example a download failed) | Fix the cause and run the same command again: it cleans up the unfinished attempt and starts over |
| The browser warns "Not secure" or shows a certificate error | The names did not point at the server when FoodStack started, so it could not get certificates. Check `nslookup`, then `./restart.sh proxy` and wait a minute ([guide 22](22-certificates.md)) |
| A warning about CPUs, memory or disk | The server is smaller than recommended. It still installs; upgrade the server before real traffic |
