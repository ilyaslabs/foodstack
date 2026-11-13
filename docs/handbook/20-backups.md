# 20 Backups and restore

[Back to the handbook](README.md)

A backup is a copy of everything on the server you cannot get back otherwise: orders, users, menus, money records and settings. Photos and files are not on the server: they are in your buckets ([guide 12](12-storage.md)), and your provider keeps them (see [Photos](#photos)). Make one every night, keep copies **off the server**, and practise a restore once before you open, so you know it works on the day you need it.

This guide is for a single-server install. On Kubernetes, see [Kubernetes](#kubernetes) at the end.

## What is in a backup

Each backup is a folder `backups/<date and time>/` in `~/foodstack` with two files:

| File | What it holds |
|---|---|
| `mongodb.archive.gz` | The database: every order, user, menu, payment and ledger entry |
| `config.tar.gz` | Your settings: `.env` and the `config` folder, with the keys, buckets, maps, email and payments |

Nothing else needs a backup. The event stream (Kafka), the cache (Redis) and store search (Elasticsearch) are rebuilt from the database.

Backups contain personal data and your keys. Keep them, and every copy, just as safe as your passwords.

### Photos

Turn on **versioning** for both buckets at your provider, so a deleted or overwritten photo can be brought back:

- Amazon S3: the bucket, **Properties**, **Bucket Versioning**, **Enable**. Add a lifecycle rule that deletes noncurrent versions after 30 days.
- Cloudflare R2: R2 keeps no old versions. For a second copy, copy both buckets to another provider every night with rclone, the same way as [the backups](#copy-backups-off-the-server).
- Backblaze B2: on by default ("Keep all versions"); set **Lifecycle Settings** to "Keep prior versions for 30 days".

## What you need

- FoodStack installed ([guide 14](14-install.md)).
- Somewhere else to keep copies: your own computer, or a storage bucket (Amazon S3, Backblaze B2, Cloudflare R2, Wasabi, or similar).
- About 20 minutes, plus an hour once for the practice restore.

## Steps

### Make a backup now

1. On the server:
   ```bash
   cd ~/foodstack
   d=backups/$(date -u +%Y%m%dT%H%M%SZ) && mkdir -p $d \
     && docker compose exec -T mongodb sh -c 'mongodump --quiet --archive --gzip -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin' > $d/mongodb.archive.gz \
     && tar -czf $d/config.tar.gz .env config && chmod -R go-rwx backups && echo "Backup done: $PWD/$d"
   ```
   It ends with `Backup done: /home/<you>/foodstack/backups/<date and time>`. The site stays up while it runs.

### Make one every night, and keep two weeks

2. Open your schedule of timed jobs:
   ```bash
   crontab -e
   ```
   (If asked which editor, choose `nano`.)
3. Add this line at the end, all on one line. It backs up at 03:00 every night (server time), writes a log, and deletes backups older than 14 days. In a crontab every `%` must be written `\%`, as here:
   ```
   0 3 * * * cd ~/foodstack && mkdir -p backups && { d=backups/$(date -u +\%Y\%m\%dT\%H\%M\%SZ) && mkdir -p $d && docker compose exec -T mongodb sh -c 'mongodump --quiet --archive --gzip -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin' > $d/mongodb.archive.gz && tar -czf $d/config.tar.gz .env config && chmod -R go-rwx backups && echo "Backup done: $d" && find backups -mindepth 1 -maxdepth 1 -type d -mtime +14 -exec rm -rf {} +; } >> backups/backup.log 2>&1
   ```
   Save with Ctrl+O, Enter, and leave with Ctrl+X.

### Copy backups off the server

A backup that lives only on the server is lost with the server. Choose one way:

- **To your own computer** (simplest). On your computer, not the server, once a week or after a big change:
  ```bash
  rsync -av <your name>@<server IP address>:foodstack/backups/ ~/foodstack-backups/
  ```
- **To a storage bucket, every night.** Install [rclone](https://rclone.org) on the server (`sudo apt -y install rclone`), run `rclone config` and follow its questions to add your bucket under the name `offsite`. Then add a second line to `crontab -e`, one hour after the backup:
  ```
  0 4 * * * rclone copy ~/foodstack/backups offsite:<your bucket>/foodstack --max-age 48h >> ~/foodstack/backups/offsite.log 2>&1
  ```
  Set the bucket to delete files after 30 to 90 days (its "lifecycle" setting), so it does not grow forever.

### Restore on the same server

Use this when data was lost or damaged but the server works. **Everything since the backup is lost**, so pick the newest good backup.

4. List the backups:
   ```bash
   cd ~/foodstack
   ls backups
   ```
5. Restore one. The site goes offline, the database is put back, the cache and search are cleared (they only hold copies), and everything starts again:
   ```bash
   b=backups/<date and time>
   docker compose stop proxy web backoffice server
   docker compose exec -T mongodb sh -c 'mongorestore --quiet --archive --gzip --drop -u foodstack -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --nsInclude "foodstack_*.*"' < $b/mongodb.archive.gz
   docker compose exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHALL'
   docker compose exec -T elasticsearch sh -c 'es="curl -fsS -u elastic:$ELASTIC_PASSWORD http://localhost:9200"; for i in $($es/_cat/indices/foodstack_search_stores_v*?h=index); do $es/$i -X DELETE; done'
   docker compose up -d
   ```
   Allow a few minutes; store search fills again a few minutes after the server is up.

### Restore on a new server (disaster recovery)

Use this when the server itself is gone.

6. Rent a new server and point your domain at it ([guides 10](10-server.md) and [11](11-domain-and-dns.md)). Do step 1 of [guide 14](14-install.md) (get the files of the **same version** as the backup), but not the rest.
7. Copy the backup folder onto the new server (from your computer or your bucket), into `~/foodstack/backups/`. For example, from your computer:
   ```bash
   rsync -av ~/foodstack-backups/<date and time> <your name>@<new server IP>:foodstack/backups/
   ```
8. Put back the old settings, with their keys. They are in the backup:
   ```bash
   cd ~/foodstack
   tar -xzf backups/<date and time>/config.tar.gz
   ```
   (Or copy `.env` and `config/application.yml` from your password manager.) The old personal-data key and signing key must be used: with new ones, nobody's details can be read and everyone is signed out.
9. Start, then restore as in step 5:
   ```bash
   docker compose up -d
   ```
   Wait until `docker compose ps` shows everything healthy, then run the commands of step 5.
10. Sign in with your **old** administrator password.

## Check it worked

- `ls ~/foodstack/backups` the morning after shows a new folder, and `backups/backup.log` ends with `Backup done`.
- Your off-server copy has the same folders.
- **The practice restore**, once before you open: follow "Restore on a new server" on a spare server (a small one for an hour is enough), sign in, and open a few past orders and a customer's details. If customers' emails show, the personal-data key is right.

## Undo

- A restore cannot be undone, but it does not delete other backups. Make a backup (step 1) just before a restore, and you can go back to that.
- To stop nightly backups, delete the line with `crontab -e`.

## If something goes wrong

| Problem | What to do |
|---|---|
| "no configuration file provided: not found" | Run the commands in `~/foodstack`, the folder with `docker-compose.yml` |
| No new backup in the morning | Read `~/foodstack/backups/backup.log`, and run the cron line by hand to see the error. "permission denied ... docker.sock": the cron job runs as a user that is not in the `docker` group; add the line with `crontab -e` as the user you sign in with, not with `sudo`. A missing `\%` makes cron cut the line short |
| The disk fills up | Keep fewer days (change `+14` to `+7`), and check `df -h /`. Copies off the server let you keep fewer on it |
| After the restore, `server` keeps restarting and `docker compose logs server` says "… does not open the accounts already stored" | `pii-key` in `config/application.yml` is not the old one. Copy it from the old settings (step 8) and `docker compose restart server`. FoodStack refuses to start with the wrong key rather than show empty details |
| After a restore on a new server, nobody can stay signed in | `signing-keys` is not the old one, or is cut short. Copy it whole: it is one long line |

## Kubernetes

The chart does not take backups for you. Use your provider's tools:

- **MongoDB**: the provider's continuous backup with point-in-time restore (MongoDB Atlas and most managed services offer it), or a nightly `mongodump` of every `foodstack_*` database.
- **Photos**: turn on versioning in your buckets ([Photos](#photos)), and copy them to another region if you want a second copy.
- **Keys**: copy the Secret `foodstack-secrets` into your password manager after the first install: `kubectl -n foodstack get secret foodstack-secrets -o yaml`.

Restore: put the database back with the provider's tool, then `kubectl -n foodstack rollout restart deploy`. Practise it every three months.
