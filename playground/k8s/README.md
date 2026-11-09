# Kubernetes playground

Try FoodStack on Kubernetes on your own computer, in any topology (monolith, split or
microservices), without renting a server. The cluster runs only FoodStack. Everything it needs
around it runs as plain Docker containers beside the cluster:

| What | Runs as | Address (from the pods) |
|---|---|---|
| Kubernetes | an EKS cluster on [Floci](https://floci.io) (a k3s node in Docker), or kind | `kubectl` through `.state/kubeconfig` |
| Photos and files | Floci's S3: buckets `foodstack-public` and `foodstack-private` | `http://10.213.0.5:4566` |
| MongoDB 8.2 | one-member replica set | `10.213.0.10:27017` |
| Redis 8.2 | single node | `10.213.0.11:6379` |
| Kafka 4.1 | one KRaft broker | `10.213.0.12:9092` |
| Elasticsearch 9.4 | single node | `http://10.213.0.13:9200` |
| Mailpit | catches every email | `10.213.0.14:1025`, read at http://localhost:8025 |
| Maps | OpenStreetMap's public servers (light use only) | |

It is for developers and for anyone checking how a split install behaves. It is not for real
data: there are no backups, and nothing is secured.

## What you need

- Docker (Docker Desktop on macOS and Windows, with at least 8 GB of memory for microservices,
  6 GB for monolith or split), `kubectl` and `helm`.
- Java 25 and Maven, to build FoodStack (the first run, and after you change code).
- For `PLAYGROUND_CLUSTER=kind`: [kind](https://kind.sigs.k8s.io).

## Start

```bash
playground/k8s/up.sh                            # monolith
playground/k8s/up.sh --topology split           # core + order + dispatch + search
playground/k8s/up.sh --topology microservices   # one Deployment per module
```

The first run takes about 10 minutes. It:

1. starts the datastores and Floci in Docker, on the network `foodstack-playground`;
2. creates the cluster and connects its node to that network;
3. installs the Gateway API and Istio in ambient mode, as the deployment guide does;
4. builds FoodStack and its three images (`ilyasdotdev/ilyaslabs-foodstack:server-playground`,
   `web-playground`, `backoffice-playground`) and loads them into the cluster;
5. installs the chart in `k8s/helm/foodstack` with [values-playground.yaml](values-playground.yaml);
6. forwards port 8080 to the gateway and prints the addresses and the administrator's password.

Then open:

- Customer app: http://foodstack.localhost:8080
- Back office: http://admin.foodstack.localhost:8080/admin/ (`admin@foodstack.localhost`, password printed by `up.sh`)
- Emails: http://localhost:8025

## Switch topology or try a change

```bash
playground/k8s/up.sh --topology microservices --skip-build   # same images, another topology
playground/k8s/up.sh --topology split                        # rebuild after changing code
```

Only the FoodStack release changes: the data stays, so the stores and orders you made are still
there after a switch. To look inside:

```bash
export KUBECONFIG=playground/k8s/.state/kubeconfig
kubectl -n foodstack get pods
kubectl -n foodstack logs deploy/foodstack-order
```

If the port forward stops (after the computer sleeps, for example), run `up.sh --skip-build`
again.

## Stop

```bash
playground/k8s/down.sh
```

It removes the cluster, the containers with their data, and the network. The images stay.

## Settings

Set any of these in the environment before `up.sh` and `down.sh`:

| Variable | Default | |
|---|---|---|
| `PLAYGROUND_CLUSTER` | `floci` | `kind` to use kind instead of Floci's EKS |
| `PLAYGROUND_PORT` | `8080` | the local port; change `publicPort` and the CORS sites to match |
| `PLAYGROUND_SUBNET` | `10.213.0` | the containers' network, if it clashes with yours; change the addresses in values-playground.yaml too |

Each datastore also starts on its own: `playground/k8s/datastores/mongodb.sh`, and so on.

## How it differs from a real install

- The datastores are single nodes, and Kafka keeps one copy of each event.
- The server does not check the buckets at start (`S3_CHECK_ON_START=false`): the pods reach
  Floci at `10.213.0.5`, while your browser loads photos from `localhost:4566`.
- Cash on delivery only: the test card gateway is refused outside a development install.
- OpenStreetMap's public servers are allowed (`maps.allowPublicOpenStreetMap`), which a real
  install refuses.
- Plain HTTP on `*.localhost`, through a port forward instead of a load balancer.

## When something is stuck

| What you see | Why, and what to do |
|---|---|
| Pods stay `Pending` or are `Evicted` with "DiskPressure" | Kubernetes stops placing pods when Docker's disk is more than about 90% full. Free space (`docker system prune`), or give Docker Desktop a bigger disk |
| Pods are `OOMKilled` or `Pending` for memory | Give Docker more memory, or use `--topology monolith` |
| `ImagePullBackOff` on a FoodStack pod | The images were not loaded: run `up.sh` without `--skip-build` |
| A datastore did not start | `docker logs fsplay-<name>` (for example `fsplay-kafka`) |
| The addresses stopped answering | The port forward ended: run `up.sh --skip-build` again |
| Address `10.213.0.x` already in use | Another network uses that range: set `PLAYGROUND_SUBNET` and change the addresses in values-playground.yaml |
