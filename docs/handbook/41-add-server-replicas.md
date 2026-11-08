# 41 Add server replicas

[Back to the handbook](README.md)

Run more copies (replicas) of FoodStack so they share the load. Any copy can answer any request, and background work spreads itself across them by itself, so adding copies is one number. No downtime, no data moves. Kubernetes only ([runbook 40](40-move-to-kubernetes.md) first).

## What you need

- FoodStack on Kubernetes, and the `my-values.yaml` you install with.
- Room in the cluster: each copy asks for half a CPU and 1.5 GB of memory by default. `kubectl top nodes` shows what is free; add machines to the cluster in your provider's console if it is nearly full.
- 10 minutes.

## How it works

Each server Deployment (`foodstack-core` until you [split modules](42-split-modules.md)) has an **autoscaler**: it adds copies when they are busy (above 70 % CPU) and removes them when quiet, between a minimum and a maximum. The defaults are 2 and 6. You change those numbers.

## Steps

1. See where you are:
   ```bash
   kubectl -n foodstack get hpa
   ```
   `REPLICAS` is how many run now; `MAXPODS` the ceiling. If it sits at the maximum at busy hours, raise the maximum.
2. In `my-values.yaml`, set the numbers for the whole server:
   ```yaml
   server:
     defaults:
       autoscaling: { enabled: true, min: 3, max: 12 }
   ```
   or for one Deployment after a split (`core`, `order`, `dispatch`, `search`):
   ```yaml
   server:
     overrides:
       order:
         autoscaling: { enabled: true, min: 3, max: 20 }
   ```
   A minimum of 3 keeps the site up while a machine is replaced.
3. Apply it, with the version you run now:
   ```bash
   helm upgrade foodstack oci://ghcr.io/ilyaslabs/charts/foodstack --version <your version> -n foodstack -f my-values.yaml
   ```
4. The front door's own copies (the gateway) scale the same way under `gateway.autoscaling`, and the customer app's under `web.autoscaling`, if those are the busy ones.

## Check it worked

- `kubectl -n foodstack get hpa` shows the new minimum and maximum, and `kubectl -n foodstack get pods` at least the minimum number of `foodstack-core` (or the split) pods, all ready.
- At the next busy hour, CPU per pod on the **FoodStack overview** dashboard stays below the line.

## Undo

Put the old numbers back and run the same `helm upgrade`. Copies are removed one by one; requests in progress finish first.

## If something goes wrong

| Problem | What to do |
|---|---|
| New pods stay `Pending` | The cluster is full. `kubectl -n foodstack describe pod <name>` says "Insufficient cpu" or "memory": add machines, or lower the minimum |
| More than 24 copies of one Deployment | Fine for requests. Events have 24 partitions, so the chart lowers the listener threads per copy by itself and copies beyond 24 only answer requests. For more event readers see [runbook 47](47-kafka.md) |
| The autoscaler shows `<unknown>` for CPU | The Metrics Server is missing ([deployment guide 4.1](../deployment-guide.md#41-cluster-prerequisites)) |
