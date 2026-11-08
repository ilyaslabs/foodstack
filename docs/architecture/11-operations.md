# 11. Running it: backups, upgrades, moves and staff accounts

[Back to index](README.md)

What the operator's tools do, traced from the unit deployer scripts (`deploy/unit-deployer/src/main/dist`), the Helm chart's migrations job, `ModuleMigrations` and the back-office screens added in M6. The step-by-step guides that use them are the [Operator handbook](../handbook/README.md).

## 11.1 Backup and restore on one server

`backup.sh` copies only what cannot be rebuilt. `restore.sh` puts it back and clears the copies that would otherwise be newer than the data.

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator (cron or by hand)
    participant B as backup.sh / restore.sh
    participant M as mongodb
    participant S as storage (RustFS)
    participant R as redis
    participant E as elasticsearch
    participant App as proxy, web, backoffice, server

    Note over Op,App: Backup (the site stays up)
    Op->>B: ./backup.sh
    B->>M: mongodump --archive --gzip (every database)
    M-->>B: backups/<ts>/mongodb.archive.gz
    B->>S: tar /data
    S-->>B: backups/<ts>/storage.tar.gz
    B->>B: tar .env, config, VERSION → config.tar.gz, then chmod go-rwx

    Note over Op,App: Restore
    Op->>B: ./restore.sh backups/<ts>
    B->>App: compose stop proxy web backoffice server
    B->>M: mongorestore --drop --nsInclude foodstack_*.*
    B->>S: delete /data, untar, restart storage
    B->>R: FLUSHALL (caches, leases, limits, live channels)
    B->>E: DELETE foodstack_search_stores_v* (by name)
    B->>App: compose up -d
    App->>E: index missing → create it and fill it from MongoDB (SearchIndexBootstrap)
```

- Kafka, Redis and Elasticsearch are never backed up: events wait in each module's outbox in MongoDB, Redis holds only short-lived copies, and the search index is rebuilt from the catalogue.
- `restore.sh` does not touch `.env`. On a new server the operator installs first, then copies only `FOODSTACK_PII_KEY` and `FOODSTACK_SIGNING_KEYS` from the old `.env`, because the new install's datastore passwords differ ([handbook 20](../handbook/20-backups.md)).
- A wrong `FOODSTACK_PII_KEY` stops the server at start: before making the first administrator, `BootstrapAdmin` decrypts the first administrator's stored email (`UserService.piiKeyOpensStoredData`) and throws if it cannot. Without this, the server started, could read no one's details, and made a second first administrator.

## 11.2 Upgrade and rollback on one server

```mermaid
flowchart TB
    U["./upgrade.sh unit-deployer-&lt;v&gt;.zip"] --> C{"zip holds<br/>app/foodstack-server.jar?"}
    C -- no --> X["stop: not a FoodStack package"]
    C -- yes --> BK["backup.sh"]
    BK --> P["copy app, compose file, VERSION, lib, scripts<br/>to .previous/"]
    P --> I["install the new files<br/>(Caddyfile and backoffice.conf.template too;<br/>.env and data untouched)"]
    I --> MG["stop the apps, run the server once with<br/>--foodstack.migrate-only=true"]
    MG --> UP["compose up -d"]

    RB["./upgrade.sh --rollback"] --> RP["stop the apps, copy .previous/ back"]
    RP --> RU["compose up -d<br/>(data stays: each release's migrations<br/>work with the release before it)"]
```

On Kubernetes the same order holds: a pre-upgrade hook Job runs every module's migrations with its own datastore override, then the Deployments roll.

## 11.3 Moving from one server to Kubernetes

```mermaid
sequenceDiagram
    autonumber
    actor Op as Operator
    participant Old as Single server
    participant NM as New MongoDB
    participant NS as S3 buckets
    participant K as Kubernetes (helm)

    Op->>K: Secret foodstack-keys: old FOODSTACK_SIGNING_KEYS and FOODSTACK_PII_KEY, new internal tokens
    Op->>Old: stop.sh proxy web backoffice server, backup.sh
    Old->>NM: mongorestore foodstack_*.* from mongodb.archive.gz
    Old->>NS: rclone copy from storage:9000 on the foodstack Docker network
    Op->>NM: rewrite-media-urls.js: OLD_BASE → NEW_BASE in every foodstack_* collection
    Op->>K: helm install (same version, secrets.existingSecret)
    K->>NM: migrations Job: change sets already applied, nothing to do
    K->>K: server pods start, empty Elasticsearch → index created and filled
    Op->>Op: test through the hosts file, then switch DNS
```

Image URLs are copied into brands, menu items and orders when they are written (`logoUrls`, `coverUrls` and the like), so a move of the photos also needs the stored URLs rewritten. `tools/migrate/rewrite-media-urls.js` walks every document of every `foodstack_*` database, replaces strings that start with the old base, and keeps BSON types (dates, longs, decimals, ids) as they are.

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
