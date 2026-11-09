# FoodStack Operator handbook

Step-by-step guides for the people who run a FoodStack marketplace: setting it up, keeping it running, and growing it as orders grow. You do not need to be a developer or a systems engineer. If you can copy a command into a terminal and click through a web page, you can follow these guides.

The [Deployment guide](../deployment-guide.md) says the same things more briefly, for engineers; on Kubernetes, the [Kubernetes guide](../kubernetes-guide.md) has every step and setting. When they disagree, tell the FoodStack team: all should be fixed.

## How every guide works

Each guide has the same five parts:

- **What you need**: accounts, passwords and time to set aside, before you start.
- **Steps**: what to type or click, in order. Commands are in grey boxes: copy them whole. Words in `<angle brackets>` are yours to fill in, without the brackets.
- **Check it worked**: what you should see. Do not go on until it is true.
- **Undo**: how to go back if something went wrong or you changed your mind.
- **If something goes wrong**: the usual causes. [Troubleshooting](90-troubleshooting.md) has more.

New words are explained in the [Glossary](99-glossary.md).

## Start here: your first launch

Do these in order. Set aside a day for the whole list; most guides take 10 to 30 minutes.

| # | Guide | What it gets you |
|---|---|---|
| 1 | [Rent a server](10-server.md) | A Linux computer on the internet, and a way to type commands on it |
| 2 | [Point your domain at it](11-domain-and-dns.md) | `example.com`, `admin.example.com` and `api.example.com` lead to your server |
| 3 | [Storage for photos and files](12-storage.md) | Two buckets at a storage provider (Amazon S3, Cloudflare R2 or Backblaze B2) where FoodStack keeps every photo and file |
| 4 | [Maps and addresses](13-maps.md) | Address search, map tiles and road distances you are allowed to use at scale |
| 5 | [Install FoodStack](14-install.md) | The marketplace running, with secure `https://` addresses |
| 6 | [Email](15-email.md) | Order updates, sign-in codes and password resets reach people |
| 7 | [Card payments](16-card-payments.md) | Customers can pay by card (optional; cash works without it) |
| 8 | [First sign-in and your team](17-first-sign-in.md) | Your own password, two-step sign-in, and accounts for your staff |
| 9 | [Your brand and rules](18-branding-and-rules.md) | Name, logo, colours, languages, fees, tax and delivery prices |
| 10 | [Your first restaurant](19-first-store.md) | A live restaurant, a test order end to end |

## Running it day to day

| Guide | When |
|---|---|
| [Backups and restore](20-backups.md) | Set up once, check every week, use when something is lost |
| [Upgrades](21-upgrades.md) | When a new FoodStack version comes out |
| [Certificates (the padlock)](22-certificates.md) | Automatic; read it if the browser ever warns about your site |
| [Health checks and dashboards](23-health-and-dashboards.md) | Every morning, and whenever something feels slow |

## Growing

Start with [Knowing when to grow](30-when-to-grow.md): it says which signal calls for which step. Then follow the runbook for that step.

| Runbook | What it does |
|---|---|
| [Move from one server to Kubernetes](40-move-to-kubernetes.md) | The big step: from one machine to a cluster, with no change to your data |
| [Add server replicas](41-add-server-replicas.md) | More copies of FoodStack to share the load |
| [Split modules](42-split-modules.md) | Give busy parts (ordering, delivery) their own machines |
| [Add MongoDB replicas](43-mongodb-replicas.md) | More copies of the database, so a machine can fail without losing anything |
| [Shard MongoDB](44-mongodb-sharding.md) | Spread the database over several machines, and add more shards later |
| [Move one module to its own database](45-module-database.md) | Give one busy part its own database cluster |
| [Grow Redis](46-redis.md) | Sentinel for failover, or Redis Cluster for more memory |
| [Grow Kafka](47-kafka.md) | More brokers, and (with care) more partitions |
| [Grow Elasticsearch](48-elasticsearch.md) | More search nodes and shards |
| [Put a CDN in front](49-cdn.md) | Serve images and store pages from near your customers |

## When things go wrong

- [Troubleshooting](90-troubleshooting.md): symptoms, causes and fixes.
- [Glossary](99-glossary.md): every technical word used here.

## Two kinds of install

FoodStack runs in two ways. The guides say which one they are for.

| | One server | Kubernetes |
|---|---|---|
| What it is | One Linux machine running everything, installed with one command | A cluster of machines managed by Kubernetes, installed with Helm |
| Good for | Launch, pilots, one city, up to about 200 orders an hour | Growth, several cities, no downtime on upgrades |
| Who can run it | Anyone who can follow these guides | Someone comfortable with Kubernetes, or a cloud provider's managed Kubernetes plus these guides |

Everyone starts on one server. Moving to Kubernetes later keeps all your data ([guide 40](40-move-to-kubernetes.md)).

## Keep these safe

Three things cannot be recovered if you lose them. Keep a copy in a password manager (1Password, Bitwarden or similar) and somewhere offline:

1. The file `/opt/foodstack/.env` on your server (one server), or the Kubernetes Secret `foodstack-secrets` (Kubernetes). It holds the **personal-data key**: without it, customers' emails and phone numbers can never be read again.
2. Your backups, copied off the server ([guide 20](20-backups.md)).
3. The administrator's password and two-step recovery codes.
