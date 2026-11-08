# 20 Backups and restore

[Back to the handbook](README.md)

A backup is a copy of everything you cannot get back otherwise: orders, users, menus, money records, photos and settings. Make one every night, keep copies **off the server**, and practise a restore once before you open, so you know it works on the day you need it.

This guide is for a single-server install. On Kubernetes, see [Kubernetes](#kubernetes) at the end.

## What is in a backup

`./backup.sh` makes a folder `backups/<date and time>/` in `/opt/foodstack` with three files:

| File | What it holds |
|---|---|
| `mongodb.archive.gz` | The database: every order, user, menu, payment and ledger entry |
| `storage.tar.gz` | Uploaded photos and files |
| `config.tar.gz` | Your settings: `.env` (with the keys), the `config` folder and the version |

Nothing else needs a backup. The event stream (Kafka), the cache (Redis) and store search (Elasticsearch) are rebuilt from the database.

Backups contain personal data and your keys. Only you can read them on the server; keep the copies just as safe.

## What you need

- FoodStack installed ([guide 12](12-install.md)).
- Somewhere else to keep copies: your own computer, or a storage bucket (Amazon S3, Backblaze B2, Cloudflare R2, Wasabi, or similar).
- About 20 minutes, plus an hour once for the practice restore.

## Steps

### Make a backup now

1. On the server:
   ```bash
   cd /opt/foodstack
   ./backup.sh
   ```
   It ends with `Backup done: /opt/foodstack/backups/<date and time>`. The site stays up while it runs.

### Make one every night, and keep two weeks

2. Open your schedule of timed jobs:
   ```bash
   crontab -e
   ```
   (If asked which editor, choose `nano`.)
3. Add this line at the end. It backs up at 03:00 every night (server time), writes a log, and deletes backups older than 14 days:
   ```
   0 3 * * * /opt/foodstack/backup.sh >> /opt/foodstack/backups/backup.log 2>&1 && find /opt/foodstack/backups -mindepth 1 -maxdepth 1 -type d -mtime +14 -exec rm -rf {} +
   ```
   Save with Ctrl+O, Enter, and leave with Ctrl+X.

### Copy backups off the server

A backup that lives only on the server is lost with the server. Choose one way:

- **To your own computer** (simplest). On your computer, not the server, once a week or after a big change:
  ```bash
  rsync -av <your name>@<server IP address>:/opt/foodstack/backups/ ~/foodstack-backups/
  ```
- **To a storage bucket, every night.** Install [rclone](https://rclone.org) on the server (`sudo apt -y install rclone`), run `rclone config` and follow its questions to add your bucket under the name `offsite`. Then add a second line to `crontab -e`, one hour after the backup:
  ```
  0 4 * * * rclone copy /opt/foodstack/backups offsite:<your bucket>/foodstack --max-age 48h >> /opt/foodstack/backups/offsite.log 2>&1
  ```
  Set the bucket to delete files after 30 to 90 days (its "lifecycle" setting), so it does not grow forever.

### Restore on the same server

Use this when data was lost or damaged but the server works. **Everything since the backup is lost**, so pick the newest good backup.

4. List the backups:
   ```bash
   cd /opt/foodstack
   ls backups
   ```
5. Restore one:
   ```bash
   ./restore.sh backups/<date and time>
   ```
   The site goes offline, the database and photos are put back, the cache and search are cleared, and everything starts again. Allow a few minutes; store search fills again a few minutes after the server is up.

### Restore on a new server (disaster recovery)

Use this when the server itself is gone.

6. Rent a new server, point your domain at it, and install FoodStack with the **same domains** ([guides 10](10-server.md), [11](11-domain-and-dns.md) and [12](12-install.md)). Do not sign in yet.
7. Copy the backup folder onto the new server (from your computer or your bucket), into `/opt/foodstack/backups/`. For example, from your computer:
   ```bash
   rsync -av ~/foodstack-backups/<date and time> <your name>@<new server IP>:/opt/foodstack/backups/
   ```
8. Put back the two keys from the old server. Open the old `.env` (from your password manager, or from the backup: `tar -xzf backups/<date and time>/config.tar.gz -O .env`), and copy these two lines over the same lines in the new `/opt/foodstack/.env`:
   ```
   FOODSTACK_PII_KEY=...
   FOODSTACK_SIGNING_KEYS=...
   ```
   Also copy your own additions (the `SMTP_`, `STRIPE_`, map and push lines). **Do not copy the whole old file**: the new install's database passwords are different, and the old ones would stop it from starting.
9. Restore:
   ```bash
   cd /opt/foodstack
   ./restart.sh server
   ./restore.sh backups/<date and time>
   ```
10. Sign in with your **old** administrator password (the new installer's password belonged to the empty database).

## Check it worked

- `ls /opt/foodstack/backups` the morning after shows a new folder, and `backups/backup.log` ends with `Backup done`.
- Your off-server copy has the same folders.
- **The practice restore**, once before you open: follow "Restore on a new server" on a spare server (a small one for an hour is enough), sign in, and open a few past orders and a customer's details. If customers' emails show, the personal-data key is right.

## Undo

- A restore cannot be undone, but `restore.sh` does not delete other backups. Run `./backup.sh` just before a restore, and you can go back to that.
- To stop nightly backups, delete the line with `crontab -e`.

## If something goes wrong

| Problem | What to do |
|---|---|
| `backup.sh` says "Run this from a FoodStack install directory" | Use the full path, `/opt/foodstack/backup.sh`, or `cd /opt/foodstack` first |
| No new backup in the morning | Read `/opt/foodstack/backups/backup.log`. "permission denied ... docker.sock": the cron job runs as a user that is not in the `docker` group; add the line with `crontab -e` as the user you sign in with, not with `sudo` |
| The disk fills up | Keep fewer days (change `+14` to `+7`), and check `./status.sh`. Copies off the server let you keep fewer on it |
| After the restore, `server` keeps restarting and `./logs.sh server` says "FOODSTACK_PII_KEY does not open the accounts already stored" | The `FOODSTACK_PII_KEY` line is not the old one. Copy it from the old `.env` (step 8) and `./restart.sh server`. FoodStack refuses to start with the wrong key rather than show empty details |
| After a restore on a new server, nobody can stay signed in | The `FOODSTACK_SIGNING_KEYS` line is not the old one, or is cut short. Copy it whole: it is one long line |

## Kubernetes

The chart does not take backups for you. Use your provider's tools:

- **MongoDB**: the provider's continuous backup with point-in-time restore (MongoDB Atlas and most managed services offer it), or a nightly `mongodump` of every `foodstack_*` database.
- **Photos**: turn on versioning in your bucket, and copy it to another region.
- **Keys**: copy the Secret `foodstack-secrets` into your password manager after the first install: `kubectl -n foodstack get secret foodstack-secrets -o yaml`.

Restore: put the database back with the provider's tool, then `kubectl -n foodstack rollout restart deploy`. Practise it every three months.
