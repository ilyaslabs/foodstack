# 43 Add MongoDB replicas

[Back to the handbook](README.md)

A MongoDB **replica set** keeps the same data on several machines. One (the primary) takes the writes; the others copy it, and one takes over within seconds if the primary fails. More members mean more safety and more room for reports and backups that read from a copy. FoodStack needs no change: it finds the members by itself. Kubernetes with a managed or operator-run MongoDB.

FoodStack reads and writes on the primary, so that a customer always sees the order they just placed. If the primary itself is too busy, the next step is [sharding](44-mongodb-sharding.md), not more members.

## What you need

- Access to your MongoDB provider's console (for example MongoDB Atlas), or to the MongoDB operator's resource if you run it yourself.
- 15 minutes, plus the time the new members take to copy the data.

## Steps

1. **Managed service**: in the cluster's settings, raise the number of members (Atlas: **Edit Configuration**, then the **Nodes** count per region, for example from 3 to 5). Members in a second region or zone protect you from a whole data centre going down. Apply.
2. **Your own replica set** (MongoDB operator or by hand): add the members as the operator's documentation says, then confirm in `mongosh`:
   ```
   rs.status().members.map(m => m.name + ' ' + m.stateStr)
   ```
3. Keep an **odd number** of voting members (3, 5 or 7), so they can always agree on a primary.
4. If your connection string lists the hosts one by one (`mongodb://host1,host2,host3/…`) instead of `mongodb+srv://`, add the new hosts to it in the Secret that holds `MONGODB_URI` and run your usual `helm upgrade`. With `mongodb+srv://` there is nothing to do.

## Check it worked

- The provider's console (or `rs.status()`) shows every member healthy, with one `PRIMARY` and the rest `SECONDARY`, and replication lag of a few seconds at most.
- FoodStack keeps working; nothing restarts.

## Undo

Lower the member count again in the console (or `rs.remove("<host:port>")`). Keep at least 3.

## If something goes wrong

| Problem | What to do |
|---|---|
| A new member stays in `STARTUP2` for hours | It is still copying the data; a large database takes a while. Check its disk is big enough |
| Orders fail for a few seconds during the change | The primary moved to another member. FoodStack retries; if errors last longer, check the connection string lists the replica set (`replicaSet=` or `mongodb+srv://`) |
