# 30 Knowing when to grow

[Back to the handbook](README.md)

Growing costs money and effort, so grow when a signal says so, not before. This guide lists the signals to watch, what each one means, and which runbook fixes it. FoodStack grows by adding machines and changing settings; the program and your data stay the same at every step ([architecture 8](../architecture/08-scaling.md) shows how).

## What you need

- A week of normal trading to compare against, including your busiest lunch and dinner.
- The morning routine from [guide 23](23-health-and-dashboards.md).

## The stages

| Stage | Fits | What it takes |
|---|---|---|
| 1. One server | Launch, one city, up to about 200 orders an hour at the busiest hour | Nothing: what you installed |
| 1b. A bigger server | Up to about twice that | Your provider's "resize" button, about 5 minutes offline |
| 2. Kubernetes, all of FoodStack together, several copies | Several cities; no downtime on upgrades; a machine can fail without the site going down | A cluster and managed databases. [Runbook 40](40-move-to-kubernetes.md), then [41](41-add-server-replicas.md) |
| 3. Busy parts on their own machines | Ordering at lunch, or live delivery maps, need far more than the rest | One setting. [Runbook 42](42-split-modules.md) |
| 4. Spread the data | The database, cache or search is what is slow | [Runbooks 43 to 49](README.md#growing) |

Most marketplaces stay at stage 1 or 2 for a long time. Stage 4 is for national scale.

## How to read the signals

### On one server

Run these at your busiest hour:

```bash
cd /opt/foodstack
./status.sh                 # all healthy, disk below 80 %
docker stats --no-stream    # CPU and memory of each service
uptime                      # the three "load average" numbers
```

| What you see at the busiest hour | What it means | Do this |
|---|---|---|
| The load average stays above the number of CPUs (`nproc` prints it) for most of the hour | The server has no spare power | First a bigger server (stage 1b). If you are already on the largest you want to pay for: [runbook 40](40-move-to-kubernetes.md) |
| `docker stats` shows memory close to the limit, or services restart by themselves | Too little memory | A bigger server |
| Disk above 80 % after deleting old backups | Orders and photos have grown | A bigger disk in the provider's control panel; then `sudo resize2fs` as the provider explains |
| Pages feel slow but CPU is low | Something outside FoodStack is slow (maps, email, the internet connection) | `./logs.sh server` and [Troubleshooting](90-troubleshooting.md) |
| You cannot accept a minute offline for upgrades or a server failure | You have outgrown one machine, whatever the load | [Runbook 40](40-move-to-kubernetes.md) |

### On Kubernetes

Look at the Grafana dashboards ([guide 23](23-health-and-dashboards.md)) over a busy week.

| Signal | Where | Do this |
|---|---|---|
| Server pods busy (CPU above 70 %) at peak, or the autoscaler at its maximum | **FoodStack overview** dashboard; `kubectl -n foodstack get hpa` | More copies: [runbook 41](41-add-server-replicas.md) |
| One part (ordering, delivery, search) has most of the traffic or errors | **FoodStack overview**, per Deployment | Split it off: [runbook 42](42-split-modules.md) |
| Database time rising, or the database provider's CPU above 70 % | **FoodStack overview** (database time); your database provider's monitoring | Reading load: [runbook 43](43-mongodb-replicas.md). Writing load or size: [runbook 44](44-mongodb-sharding.md). One part much busier than the rest: [runbook 45](45-module-database.md) |
| Redis memory above 70 %, or its CPU high | Your Redis provider's monitoring | [Runbook 46](46-redis.md) |
| Consumer lag keeps growing at peak and returns to zero only at night | **FoodStack events** dashboard | More copies of the part that lags ([runbook 41](41-add-server-replicas.md)). If it already has 24 readers, [runbook 47](47-kafka.md) |
| Store search slow (search requests above 300 ms) | **FoodStack overview**, search routes | [Runbook 48](48-elasticsearch.md) |
| Customers far from your servers wait long for store pages and photos | Your uptime service's response times from other regions | [Runbook 49](49-cdn.md) |

## Steps

1. Pick the one signal that is worst. Fix one thing at a time.
2. Follow its runbook in a quiet hour.
3. Compare the same busy hour a week later. If the signal is gone, stop there.

## Check it worked

The signal you acted on is back in its normal range at the next busy hour, and nothing else got worse.

## Undo

Every runbook has its own **Undo**. Adding machines can always be undone by removing them again; only moving to Kubernetes and sharding are one-way steps, and those runbooks say so.

## If something goes wrong

| Problem | What to do |
|---|---|
| Two signals at once | Fix the one that customers feel (errors, slow checkout) first |
| You are not sure what a signal means | Send the dashboard screenshot and `./status.sh` output to the FoodStack team before buying machines |
