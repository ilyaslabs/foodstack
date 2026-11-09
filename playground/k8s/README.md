# Kubernetes playground

Try FoodStack on Kubernetes on your own computer, in any topology (monolith, split or
microservices), without renting a server. The cluster runs only FoodStack; everything around it
runs as plain Docker containers.

| What | Runs as | Address (from the pods) |
|---|---|---|
| Kubernetes, photo buckets | [Floci](https://floci.io) (local AWS): an EKS cluster and S3 (plus ECR only to try your own builds), which you set up with the usual `aws` and `kubectl` commands below | Floci at `10.213.0.5:4566` |
| MongoDB 8.2 | one-member replica set | `10.213.0.10:27017` |
| Redis 8.2 | single node | `10.213.0.11:6379` |
| Kafka 4.1 | one KRaft broker | `10.213.0.12:9092` |
| Elasticsearch 9.4 | single node | `http://10.213.0.13:9200` |
| Mailpit | catches every email | `10.213.0.14:1025`, read at http://localhost:8025 |
| Maps | OpenStreetMap's public servers (light use only) | |

The scripts here start the datastores and install FoodStack on whatever cluster `kubectl` points
at; they do not drive Floci. It is for developers and for anyone checking how a split install
behaves. Not for real data: no backups, nothing secured.

## What you need

- Docker (Docker Desktop on macOS and Windows, with at least 8 GB of memory for microservices,
  6 GB for monolith or split), the AWS CLI, `kubectl` and `helm`.
- Only to try your own unreleased changes: Java 25 and Maven.

## 1. Floci: cluster and buckets

Floci and the datastores share one Docker network, so the pods reach them on fixed addresses:

```bash
docker network create --subnet 10.213.0.0/24 --ip-range 10.213.0.128/25 foodstack-playground
docker run -d --name floci --network foodstack-playground --ip 10.213.0.5 -p 4566:4566 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e FLOCI_SERVICES_EKS_DOCKER_NETWORK=foodstack-playground floci/floci:2.4.0

export AWS_ENDPOINT_URL=http://localhost:4566 AWS_DEFAULT_REGION=us-east-1
```

The EKS cluster, and `kubectl` pointed at it as for any EKS cluster. Floci wants a real-style key
for cluster access (not `test`/`test`):

```bash
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws iam create-user --user-name eks-admin
read AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY < <(AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws iam create-access-key --user-name eks-admin --query 'AccessKey.[AccessKeyId,SecretAccessKey]' --output text)
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY

aws eks create-cluster --name foodstack --role-arn arn:aws:iam::000000000000:role/eks \
  --resources-vpc-config subnetIds=subnet-default-us-east-1-a
aws eks wait cluster-active --name foodstack
aws eks update-kubeconfig --name foodstack
kubectl get nodes
```

The two buckets, as an operator sets them up at Amazon (handbook guide 12): CORS for the two
sites, and public reading on `foodstack-public`. The app reads the key `playground` /
`playground-secret` (values-playground.yaml); Floci does not check it.

```bash
aws s3 mb s3://foodstack-public && aws s3 mb s3://foodstack-private
cors='{"CORSRules":[{"AllowedOrigins":["http://foodstack.localhost:8080","http://admin.foodstack.localhost:8080"],
  "AllowedMethods":["GET","PUT"],"AllowedHeaders":["*"],"MaxAgeSeconds":3000}]}'
aws s3api put-bucket-cors --bucket foodstack-public --cors-configuration "$cors"
aws s3api put-bucket-cors --bucket foodstack-private --cors-configuration "$cors"
aws s3api put-bucket-policy --bucket foodstack-public --policy '{"Version":"2012-10-17","Statement":[
  {"Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::foodstack-public/*"}]}'
```

## 2. FoodStack

A released version, straight from Docker Hub (`ilyasdotdev/ilyaslabs-foodstack:server-1.0.0`,
`web-1.0.0`, `backoffice-1.0.0`). The cluster pulls the images itself; nothing is built. Run it from a checkout of the same release
(`git checkout v1.0.0`), so the chart matches the images:

```bash
playground/k8s/up.sh --version 1.0.0                           # monolith
playground/k8s/up.sh --version 1.0.0 --topology split          # core + order + dispatch + search
playground/k8s/up.sh --version 1.0.0 --topology microservices  # one Deployment per module
```

`up.sh`:

1. starts the datastores (`datastores/*.sh`, each can also run on its own);
2. installs the Gateway API and Istio in ambient mode on the cluster, once, as the deployment guide does;
3. installs the chart in `k8s/helm/foodstack` with [values-playground.yaml](values-playground.yaml);
4. forwards port 8080 to the gateway and prints the addresses and the administrator's password.

Then open:

- Customer app: http://foodstack.localhost:8080
- Back office: http://admin.foodstack.localhost:8080/admin/ (`admin@foodstack.localhost`, password printed by `up.sh`)
- Emails: http://localhost:8025

Run it again with another `--topology` to switch. Only the FoodStack release changes; the stores
and orders you made are still there.

`up.sh` works the same on any other cluster your `kubectl` reaches (kind, k3s, Docker Desktop's
Kubernetes), as long as its pods can reach the `foodstack-playground` network.

## 3. Optional: try your own unreleased changes

Images built from your working copy exist only on your computer, so the cluster cannot get them
from Docker Hub. Push them to Floci's ECR instead, which the cluster pulls from as it is:

```bash
aws ecr create-repository --repository-name foodstack --query repository.repositoryUri --output text
# 000000000000.dkr.ecr.us-east-1.localhost:4566/foodstack

playground/k8s/up.sh --registry 000000000000.dkr.ecr.us-east-1.localhost:4566/foodstack --topology split
playground/k8s/up.sh --registry ... --topology microservices --skip-build   # same images, another topology
```

With `--registry`, `up.sh` builds FoodStack (Maven, pnpm) and its three images
(`server-playground`, `web-playground`, `backoffice-playground`), pushes them, and restarts the
pods on the new build. The first run takes about 10 minutes.

On Linux with Docker's containerd image store (the default from Docker Engine 29), Docker can
only push to the path form of that address (Floci's ECR guide); the cluster still pulls from the
address above. Add `--push-to localhost:4566/000000000000/us-east-1/foodstack`.

## Stop

```bash
playground/k8s/down.sh                                 # FoodStack and the datastores, with their data
aws eks delete-cluster --name foodstack                # the cluster
docker rm -f floci && docker network rm foodstack-playground
```

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
| `kubectl cannot reach a cluster` | Run `aws eks update-kubeconfig --name foodstack` again (with the eks-admin key exported) |
| Pods stay `Pending` or are `Evicted` with "DiskPressure" | Kubernetes stops placing pods when Docker's disk is about 90% full. Free space (`docker system prune`), or give Docker Desktop a bigger disk |
| Pods are `OOMKilled` or `Pending` for memory | Give Docker more memory, or use `--topology monolith` |
| `ImagePullBackOff` | With `--version`: that version is not on Docker Hub (see the GitHub releases). With `--registry`: run without `--skip-build`, and check `aws ecr list-images --repository-name foodstack` |
| A datastore did not start | `docker logs fsplay-<name>` (for example `fsplay-kafka`) |
| The addresses stopped answering | The port forward ended: run `up.sh ... --skip-build` again |
| Address `10.213.0.x` already in use | Another network uses that range: set `PLAYGROUND_SUBNET` and change the addresses in values-playground.yaml and the commands above |
