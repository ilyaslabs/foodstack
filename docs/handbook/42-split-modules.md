# 42 Split modules

[Back to the handbook](README.md)

FoodStack is made of parts (modules): ordering, delivery dispatch, search, stores, payments and so on. At first one Deployment runs them all. When one part needs far more than the rest (ordering at lunch, search on a busy evening), give it its own Deployment, so it grows on its own machines without copying everything else. It is one setting. **No data moves**, and there is no downtime. Kubernetes only.

## What you need

- FoodStack on Kubernetes and your `my-values.yaml`.
- A signal that one part is the busy one ([guide 30](30-when-to-grow.md)).
- 20 minutes.

## The choices

| `topology` | Deployments | When |
|---|---|---|
| `monolith` (default) | `core` runs every module | Until one part dominates |
| `split` | `order`, `dispatch` and `search` on their own; `core` runs the rest | The usual next step |
| `microservices` | One Deployment per module (15) | Very large installs with a team to watch them |
| `custom` | Your own groups, listed under `server.deployments` | When `split` is close but not right |

## Steps

1. In `my-values.yaml`, set:
   ```yaml
   topology: split
   ```
   The repository's `values-split.yaml` has good starting sizes for each Deployment; copy its `server.overrides` into your file, or add it as a second `-f` file.
2. Apply it:
   ```bash
   helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version <your version> -n foodstack -f my-values.yaml
   ```
   The new Deployments start; when they are ready, the gateway sends each part's requests to its own Deployment and `core` stops running those modules. Calls between parts are retried while they move, so nothing is lost.
3. **Your own groups** (optional), instead of `split`. Every module must appear exactly once:
   ```yaml
   topology: custom
   server:
     deployments:
       core:     { modules: [identity, settings, geo, media, store, cart, promotion, payment, review, notification, ledger] }
       order:    { modules: [order], replicas: 3, autoscaling: { enabled: true, min: 3, max: 20 } }
       delivery: { modules: [rider, dispatch], replicas: 2 }
       search:   { modules: [search], replicas: 2 }
   ```

## Check it worked

- `kubectl -n foodstack get deploy` lists `foodstack-core`, `foodstack-order`, `foodstack-dispatch` and `foodstack-search` (or your groups), each with all pods ready.
- `kubectl -n foodstack logs deploy/foodstack-order | grep -i module` lists `order` as the module running locally.
- A test order goes through end to end, and the **FoodStack overview** dashboard shows traffic per Deployment.

## Undo

Set `topology: monolith` and run the same `helm upgrade`. The parts move back into `core`; no data moves either way.

## If something goes wrong

| Problem | What to do |
|---|---|
| `helm upgrade` fails with "module … is not run by any server Deployment" or "is listed in both" | A `custom` list misses or repeats a module. The error names it |
| Requests to one part fail with 503 for a minute during the change | Its new Deployment was not ready yet; it clears itself. If it does not, `kubectl -n foodstack get pods` shows the pod that is not ready, and its logs say why |
