# 46 Grow Redis

[Back to the handbook](README.md)

Redis holds FoodStack's short-lived data: caches, sign-in limits, live order updates, riders' positions, and who does which background job. Nothing in it is lost for good if Redis restarts; FoodStack rebuilds it. Two ways to grow it, both with no code change. Kubernetes with a managed or operator-run Redis 7 or newer.

| Setup | What it gives | When |
|---|---|---|
| **Sentinel**: a primary, one or more replicas, and Sentinel watchers | If the primary fails, a replica takes over in seconds | You need Redis to survive a machine failure (most managed Redis "with replicas" do this for you behind one address) |
| **Redis Cluster**: data spread over several primaries, each with a replica | More memory and more commands per second | Redis memory above 70 %, or its CPU busy at peak ([guide 30](30-when-to-grow.md)) |

Every FoodStack command uses a single key and live updates use sharded channels on a cluster, so both work as they are.

## What you need

- The new Redis: for Sentinel, the master name and the Sentinel addresses; for Cluster, the addresses of its nodes; and the password.
- `my-values.yaml`.
- 20 minutes at a quiet time. People stay signed in, but live order pages reconnect and caches start empty.

## Steps

1. Keep `datastores.redis.url` pointing at the new Redis with its password (any one node, for example `rediss://:<password>@<node 1>:6379`); FoodStack takes the password from it.
2. Add the topology under `server.env` in `my-values.yaml`:

   **Sentinel**:
   ```yaml
   server:
     env:
       SPRING_DATA_REDIS_SENTINEL_MASTER: <master name, often mymaster>
       SPRING_DATA_REDIS_SENTINEL_NODES: <sentinel1:26379,sentinel2:26379,sentinel3:26379>
   ```
   **Redis Cluster**:
   ```yaml
   server:
     env:
       SPRING_DATA_REDIS_CLUSTER_NODES: <node1:6379,node2:6379,node3:6379>
   ```
   A managed service that gives one address for its whole replicated Redis (for example ElastiCache with cluster mode off) needs only step 1.
3. The front door's rate limits keep their counters in Redis too. Tell it the new shape under `gateway.rateLimit.redis`:
   ```yaml
   gateway:
     rateLimit:
       redis:
         type: cluster              # single | cluster | sentinel
         address: <node1:6379,node2:6379,node3:6379>
         tls: true                  # if the address is rediss://
   ```
   For Sentinel, the address is the master name and then the Sentinels: `mymaster,sentinel1:26379,sentinel2:26379`. With `type: single` and no `address`, it uses `datastores.redis.url`, which is right for step 1 alone.
4. Apply:
   ```bash
   helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version <your version> -n foodstack -f my-values.yaml
   ```
   The server pods restart one by one with the new connection.
5. When everything works for a day, delete the old Redis.

## Check it worked

- The server pods' logs show no Redis errors: `kubectl -n foodstack logs deploy/foodstack-core | grep -i redis`.
- Open an order's page in the customer app and change its status in the back office: the page updates by itself.
- A rider's position moves on **Dispatch** (if you run riders).

## Undo

Remove the `SPRING_DATA_REDIS_…` lines, point `datastores.redis.url` back at the old Redis, and upgrade again.

## If something goes wrong

| Problem | What to do |
|---|---|
| "MOVED" or "CROSSSLOT" errors | The cluster is used without `SPRING_DATA_REDIS_CLUSTER_NODES`. Add it (step 2) |
| "NOAUTH" or "WRONGPASS" | The password in `datastores.redis.url` is not the new one. With Sentinel, the Sentinels may have their own password: add `SPRING_DATA_REDIS_SENTINEL_PASSWORD` |
| Live order pages stop updating | Redis is reachable but the pods run with different settings. `kubectl -n foodstack rollout restart deploy` so all copies use the new one |
