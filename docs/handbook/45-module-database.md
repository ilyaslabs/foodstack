# 45 Move one module to its own database

[Back to the handbook](README.md)

Each FoodStack part (module) keeps its data in its own database (`foodstack_order`, `foodstack_store`, and so on), all on one MongoDB cluster at first. When one part is far busier than the rest, or must be kept apart (for example payments and the ledger, for audits), move its database to a cluster of its own. Only that part's connection changes. Kubernetes only.

## What you need

- A new MongoDB cluster (a replica set of 3, 7.1 or newer), and its connection string.
- `my-values.yaml`, and Docker on a computer that can reach both clusters (for the copy).
- A maintenance window of 15 to 60 minutes, depending on the database's size: the module's data must not change while it is copied.

The modules are: `identity`, `settings`, `media`, `geo`, `store`, `search`, `promotion`, `cart`, `payment`, `order`, `rider`, `dispatch`, `notification`, `ledger`, `review`. Their databases are `foodstack_<module>`.

## Steps

1. **Stop the server** so nothing writes during the copy. Note the current replica counts first (`kubectl -n foodstack get deploy`), then:
   ```bash
   kubectl -n foodstack scale deploy foodstack-core --replicas=0
   ```
   After a [split](42-split-modules.md), scale every server Deployment (`foodstack-order`, `foodstack-dispatch`, `foodstack-search` too). The customer app shows that the service is unavailable.
2. **Copy the module's database**, here `order`:
   ```bash
   docker run --rm mongo:8.2 sh -c "mongodump --uri '<old connection string>' --db foodstack_order --archive \
     | mongorestore --uri '<new connection string>' --archive --drop"
   ```
3. **Give FoodStack the new address** in a Secret with the key `MONGODB_URI`:
   ```bash
   kubectl -n foodstack create secret generic order-mongodb --from-literal=MONGODB_URI='<new connection string>'
   ```
   and in `my-values.yaml`:
   ```yaml
   server:
     moduleDatastores:
       order: { mongodbUriSecret: order-mongodb }
   ```
4. **Start again**:
   ```bash
   helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version <your version> -n foodstack -f my-values.yaml
   ```
   The upgrade sets the replica counts back. The `order` module now uses its own cluster; every other module stays where it was.
5. After a week without problems, delete the old copy: in `mongosh` on the old cluster, `db.getSiblingDB('foodstack_order').dropDatabase()`.

## Check it worked

- `kubectl -n foodstack get pods` shows every server pod ready.
- Past and new orders show in **Orders**, and a test order goes through end to end.
- On the new cluster, `db.getSiblingDB('foodstack_order').order_orders.countDocuments()` grows with new orders; on the old one it stays still.

## Undo

Before step 5: remove the `moduleDatastores` entry and run the same `helm upgrade`. The module goes back to the old database, **without** the orders placed in between; to keep them, copy the database back first (step 2 the other way round, with the server stopped).

## If something goes wrong

| Problem | What to do |
|---|---|
| Server pods fail with "Timed out … while waiting for a server" | The new cluster cannot be reached from Kubernetes: its network access list must allow the cluster's addresses |
| Pods stay in `CreateContainerConfigError` | The Secret is missing or its key is not `MONGODB_URI`: `kubectl -n foodstack describe secret order-mongodb` |
| The copy says "not authorized" | The new cluster's user needs to write to `foodstack_order` (for example `readWriteAnyDatabase`) |
