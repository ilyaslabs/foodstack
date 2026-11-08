# 48 Grow Elasticsearch

[Back to the handbook](README.md)

Elasticsearch answers "which restaurants deliver here, and which have this dish". Its index is a copy built from the database, so it can always be rebuilt. It grows with more **nodes** (machines) and, for very large catalogues, more **shards**. Kubernetes with Elastic Cloud or the Elastic operator (ECK).

## What you need

- A signal: search requests above 300 ms at peak, or the Elasticsearch nodes' CPU high ([guide 30](30-when-to-grow.md)).
- Your Elasticsearch console, and `my-values.yaml`.
- 20 minutes. More shards need a rebuild of the index: a few minutes during which search may show fewer restaurants.

## Steps

### More nodes

1. Add nodes in the console (Elastic Cloud: **Edit deployment**, more size or more zones), or raise `count` in the ECK resource.
2. FoodStack keeps a copy of the index on up to 2 nodes by default (`auto_expand_replicas` `0-1`). To keep a copy on **every** node, so every node can answer searches, add to `my-values.yaml`:
   ```yaml
   server:
     env:
       FOODSTACK_SEARCH_AUTOEXPANDREPLICAS: 0-all
   ```
   and run `helm upgrade`. It applies to the live index when the server starts.

### More shards (large catalogues only)

One shard is right up to tens of thousands of restaurants. More shards help only beyond that.

3. In `my-values.yaml`:
   ```yaml
   server:
     env:
       FOODSTACK_SEARCH_SHARDS: "3"
   ```
4. Run `helm upgrade`. The setting is for a **new** index, so then delete the current one, and FoodStack makes a new one with 3 shards and fills it from the database:
   ```bash
   curl -u elastic:<password> '<Elasticsearch URL>/_cat/indices/foodstack_search_stores_v*?h=index'
   curl -u elastic:<password> -X DELETE '<Elasticsearch URL>/<the index name it printed>'
   kubectl -n foodstack rollout restart deploy foodstack-core
   ```
   (After a [split](42-split-modules.md), restart `foodstack-search` instead.) Search is empty or partial for a few minutes while it fills.

## Check it worked

- The console shows the cluster **green** with every node.
- `curl -u elastic:<password> '<Elasticsearch URL>/_cat/indices/foodstack_search_stores_v*?v'` shows the replica count (`rep`) and, after step 4, `pri` equal to your shard count and `docs.count` equal to your live restaurants.
- The customer app finds restaurants near you.

## Undo

Remove the lines from `my-values.yaml` and `helm upgrade`. For shards, repeat step 4 to rebuild with one shard.

## If something goes wrong

| Problem | What to do |
|---|---|
| The cluster is **yellow** | Some copies have no node to go on, usually with one node and replicas above 0. It still works; add a node, or leave `0-1` |
| Search stays empty after step 4 | The server's log says why: `kubectl -n foodstack logs deploy/foodstack-core | grep -i search`. A restart rebuilds an empty index |
| Search shows a restaurant that has closed | Updates arrive within seconds; a full re-read runs every 24 hours and fixes anything missed |
