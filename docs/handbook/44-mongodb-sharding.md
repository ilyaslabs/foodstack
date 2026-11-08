# 44 Shard MongoDB, and add a shard

[Back to the handbook](README.md)

**Sharding** spreads the data over several replica sets (shards), so writes and storage grow with the number of machines. Each FoodStack collection already has a chosen shard key (for orders, the order id; for a store's orders, the store), so the data spreads evenly and the busy queries go to one shard. Every FoodStack query is tested on a two-shard cluster. Kubernetes with a managed or operator-run MongoDB 7.1 or newer.

**One-way step**: a sharded cluster cannot be turned back into a replica set without moving the data.

## What you need

- A signal that the primary is the bottleneck: CPU above 70 % or disk writes saturated at busy hours, after [runbook 43](43-mongodb-replicas.md) ([guide 30](30-when-to-grow.md)).
- Your MongoDB provider's console, `my-values.yaml`, and a fresh backup ([guide 20](20-backups.md#kubernetes)).
- An hour at a quiet time. The conversion itself does not stop the site, but plan for a few slow minutes.

## Steps

### Turn the replica set into a sharded cluster

1. **Managed service**: in the cluster's settings, change the type to **Sharded cluster** with 2 shards (Atlas: **Edit Configuration**, **Cluster Tier**, then **Shard your cluster**). Apply and wait until the console shows the change done. **Your own cluster**: follow MongoDB's guide "Convert a replica set to a sharded cluster": the replica set becomes the first shard, you add config servers and `mongos` routers, then a second shard.
2. Get the new **connection string**: it now points at the `mongos` routers. (Atlas keeps the same `mongodb+srv://` address.) If it changed, update the Secret that holds `MONGODB_URI`.
3. Tell FoodStack to apply its shard keys. In `my-values.yaml`:
   ```yaml
   datastores:
     mongodb:
       sharding: true
   ```
4. Apply it:
   ```bash
   helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version <your version> -n foodstack -f my-values.yaml
   ```
   The migrations job builds an index on each collection's shard key, then shards the collection. On a large database the indexes take a while; the job waits for them. MongoDB then moves part of the data to the second shard in the background, over hours, without stopping anything.

### Add a shard later

5. In the provider's console, raise the number of shards (or add a replica set as a shard with `sh.addShard()`). FoodStack needs nothing: MongoDB moves data to the new shard by itself.

## Check it worked

- In `mongosh` connected to the new connection string:
  ```
  db.getSiblingDB('config').collections.countDocuments({ _id: /^foodstack_/, unsplittable: { $ne: true } })
  ```
  is above 50 (every collection with a declared key is sharded), and `sh.status()` lists both shards with data on each after a few hours.
- The migrations job's log shows lines "Sharded foodstack_… on {…}": `kubectl -n foodstack logs -l app.kubernetes.io/name=foodstack-migrations` (while the job is still there), or the first server pod's log.
- A test order goes through, and the **FoodStack overview** dashboard shows database time back to normal at the next busy hour.

## Undo

- Before step 4: switch the cluster back in the console, if the provider allows it.
- After step 4: there is no going back in place. To return to a replica set, dump the databases and restore them into a new replica set ([runbook 45](45-module-database.md) shows the copy), then set `sharding: false`.
- Fewer shards: the provider can remove a shard; MongoDB first moves its data to the others.

## If something goes wrong

| Problem | What to do |
|---|---|
| The migrations job fails with "requires mongos" or "no such command: shardCollection" | The connection string still points at the old replica set, not the routers. Fix the Secret and upgrade again |
| The job times out on a very large database | Raise `migrations.activeDeadlineSeconds` (default 900) and upgrade again: shards that are done are not redone |
| Data stays on one shard | The balancer runs in the background and can take hours. `sh.isBalancerRunning()` should be true; some providers limit it to a time window |
