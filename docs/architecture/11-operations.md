# 11. Running it: backups, upgrades, moves and staff accounts

[Back to index](README.md)

What the operator's tools do, traced from the Docker Compose commands in the handbook ([deploy/compose](../../deploy/compose/README.md)), the Helm chart's migrations job, `ModuleMigrations` and the back-office screens added in M6. The step-by-step guides that use them are the [Operator handbook](../handbook/README.md).

## 11.1 Backup and restore on one server

There are no scripts: the handbook ([guide 20](../handbook/20-backups.md)) gives plain `docker compose` commands, which a cron line runs nightly. A backup copies only what cannot be rebuilt and lives on the server: MongoDB and the two settings files. Photos and files are in the operator's own buckets (versioned at the provider). A restore puts MongoDB back and clears the copies that would otherwise be newer than the data.

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator (cron or by hand)
    participant M as mongodb
    participant R as redis
    participant E as elasticsearch
    participant App as proxy, web, backoffice, server

    Note over Op,App: Backup (the site stays up)
    Op->>M: docker compose exec -T mongodb mongodump --archive --gzip
    M-->>Op: backups/<ts>/mongodb.archive.gz
    Op->>Op: tar .env and config/ → backups/<ts>/config.tar.gz, chmod go-rwx

    Note over Op,App: Restore
    Op->>App: docker compose stop proxy web backoffice server
    Op->>M: mongorestore --drop --nsInclude foodstack_*.*
    Op->>R: FLUSHALL (caches, leases, limits, live channels)
    Op->>E: list foodstack_search_stores_v* with _cat, DELETE each by name
    Op->>App: docker compose up -d
    App->>E: index missing → create it and fill it from MongoDB (SearchIndexBootstrap)
```

- Kafka, Redis and Elasticsearch are never backed up: events wait in each module's outbox in MongoDB, Redis holds only short-lived copies, and the search index is rebuilt from the catalogue.
- Elasticsearch 9 refuses wildcard deletes (`action.destructive_requires_name`), so the restore lists the indices and deletes them one by one.
- On a new server the operator unpacks `config.tar.gz` before the first `docker compose up -d`. The old `.env` is safe to reuse there because the new MongoDB volume is empty and takes its password from it; `pii-key` and `signing-keys` come back with `config/application.yml` ([handbook 20](../handbook/20-backups.md)).
- A wrong `pii-key` stops the server at start: before making the first administrator, `BootstrapAdmin` decrypts the first administrator's stored email (`UserService.piiKeyOpensStoredData`) and throws if it cannot. Without this, the server started, could read no one's details, and made a second first administrator.

## 11.2 Upgrade and rollback on one server

```mermaid
flowchart TB
    BK["back up (11.1), copy docker-compose*.yml<br/>and Caddyfile to previous/"] --> Z["unzip foodstack-compose-&lt;v&gt;.zip,<br/>copy its compose files, Caddyfile, local/"]
    Z --> D["diff .env and config/application.yml<br/>against the new samples, add new settings"]
    D --> V["FOODSTACK_VERSION=&lt;v&gt; in .env"]
    V --> UP["docker compose pull && up -d<br/>(the server runs every module's migrations on start)"]

    RB["rollback"] --> RP["copy previous/ back,<br/>old FOODSTACK_VERSION"]
    RP --> RU["docker compose up -d<br/>(data stays: each release's migrations<br/>work with the release before it)"]
```

On one machine there is one server, so it migrates as it starts. On Kubernetes, with many pods, a pre-upgrade hook Job runs every module's migrations with its own datastore override (`--foodstack.migrate-only=true`), then the Deployments roll.

## 11.3 Moving from one server to Kubernetes

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator
    participant Old as Single server
    participant NM as New MongoDB
    participant NS as S3 buckets
    participant K as Kubernetes (helm)

    Op->>K: Secret foodstack-keys: old pii-key and signing-keys, new internal tokens
    Op->>Old: docker compose stop proxy web backoffice server, mongodump
    Old->>NM: mongorestore foodstack_*.* from mongodb.archive.gz
    Op->>K: helm install (same version, secrets.existingSecret,<br/>datastores.s3 and maps copied from the old application.yml)
    K->>NS: StorageCheck: same buckets, CORS already allows the same two sites
    K->>NM: migrations Job: change sets already applied, nothing to do
    K->>K: server pods start, empty Elasticsearch → index created and filled
    Op->>Op: test through the hosts file, then switch DNS
```

The buckets stay where they are, so photos are not copied and stored image URLs stay valid. Image URLs are copied into brands, menu items and orders when they are written (`logoUrls`, `coverUrls` and the like), so a change of `foodstack.storage.public-base-url` (a CDN, handbook guide 49) needs the stored URLs rewritten. `tools/migrate/rewrite-media-urls.js` walks every document of every `foodstack_*` database, replaces strings that start with the old base, and keeps BSON types (dates, longs, decimals, ids) as they are.

The other moves (to separate services, regrouping, back) are in the [migration guide](../migration-guide.md).

## 11.4 Sharding a database that already has data

`datastores.mongodb.sharding: true` sets `FOODSTACK_MONGODB_SHARDING_ENABLED`, and every module's `ModuleMigrations` then applies its declared keys on start (the migrations Job, then each pod, which finds them done).

```mermaid
flowchart TB
    S["ModuleMigrations.applyShardKeys()"] --> EN["enableSharding foodstack_&lt;module&gt;"]
    EN --> L["for each declared key: outbox (bucket, _id), inbox hashed _id,<br/>and the module's own keys (TDD 6.3)"]
    L --> Q{"config.collections says<br/>already sharded?"}
    Q -- yes --> N["skip"]
    Q -- no --> IX["createIndex(key)<br/>an existing index with the same keys<br/>under another name (error 85) is fine"]
    IX --> SH["shardCollection(namespace, key)"]
```

The index step is what lets a replica set with data become a sharded cluster: MongoDB creates the shard key's index by itself only for an empty collection. `ModuleMigrationsTest.shardsPopulatedCollection` covers it on the two-shard test cluster.

## 11.5 New staff account and Change password (back office)

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    actor U as Any signed-in user
    participant BO as Back office
    participant ID as identity (UsersController, MeController, UserService)
    participant DB as foodstack_identity

    A->>BO: Users, New staff account (name, email, first password, Support | Finance | Admin)
    BO->>ID: POST /api/v1/users (identity:users:manage)
    ID->>ID: roles must be ADMIN, FINANCE or SUPPORT, else 400 USER_ROLES_INVALID
    ID->>DB: insert identity_users (email taken → 409 EMAIL_TAKEN) and UserRegistered in the outbox
    ID-->>BO: the account, shown first in the list: "<name> can sign in now"

    U->>BO: account menu, Change password (current, new twice)
    BO->>ID: PUT /api/v1/me/password
    ID->>ID: current password checked (400 PASSWORD_WRONG), new one at least 10 characters
    ID->>DB: new password hash
```

Restaurant owners and riders are not made here: owners from a branch's **Managers** tab, riders from **Riders**.
