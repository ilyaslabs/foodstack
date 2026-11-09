# FoodStack

FoodStack is an open-source food ordering and delivery platform. An operator can launch their own branded delivery marketplace (customer app, back office for admins, stores and riders, REST API, Kubernetes deployment) without first building a tech team.

The backend is a Spring Boot 4 / Java 25 modular monolith built on [Module-Switch](https://github.com/ilyaslabs/module-switch), so any module can move into its own service by changing configuration only.

## Status

Milestones M0 to M6 are built: ordering, payments, delivery, money and trust, production
hardening (the Kubernetes chart for every topology, two-step and Google or Apple sign-in,
failed-event replay, search ranking, the observability chart), and the
[Operator handbook](docs/handbook/README.md) with a [manual QA suite](qa/README.md). The v1.0
release is next, once someone who did not write the handbook has followed it end to end. What
each milestone does differently from the design is in
[TDD sections 21 to 28](docs/TDD.md#28-m6-implementation-notes); how the code works today is
drawn in [docs/architecture](docs/architecture/README.md).

## Repository

| Path | What |
|---|---|
| `platform/` | Shared libraries: ids and money, per-module MongoDB with Liquibase and the outbox, Kafka relay, security, web errors, test containers |
| `modules/<module>/` | One module in four jars: `-api` (contract), `-core` (logic and data), `-impl` (module API), `-web` (REST) |
| `app/foodstack-server` | The server: every module, switched local, HTTP or off by configuration |
| `integration-tests/` | Business journeys through the REST API, each run as a monolith and as one service per module |
| `frontend/` | Angular workspace: `web` (SSR customer app), `backoffice`, shared libraries |
| `deploy/unit-deployer/` | Docker Compose, Caddy and scripts for one server, packaged as `unit-deployer-<version>.zip` |
| `k8s/helm/foodstack` | Helm chart: any topology, Gateway API, Istio ambient, edge rate limits ([deployment guide 4](docs/deployment-guide.md#4-kubernetes-helm)) |
| `k8s/helm/foodstack-observability` | Prometheus, Grafana dashboards and alerts, Loki, Tempo, OpenTelemetry Collector |
| `tools/k6/` | Load test of customer browsing |
| `tools/migrate/` | One-off data scripts for the runbooks (image addresses after moving photos) |
| `qa/` | Manual QA test cases, one file per area, with the database records to check |

## Build and run

Prerequisites: Java 25, Maven, Docker; Node 24 and pnpm for the front-end.

```bash
mvn -T 1C test                            # every test, including both integration topologies,
                                          # one module per CPU core side by side
mvn -pl app/foodstack-server -am install -DskipTests
# Run the server with containers: LocalFoodstackApplication (app/foodstack-server/src/test) from the IDE,
# or: mvn -pl app/foodstack-server spring-boot:test-run   (server on http://localhost:8080), then
cd frontend && pnpm install && pnpm start:backoffice   # http://localhost:4300/admin/
pnpm start:web                                          # http://localhost:4200/ (storefront)
```

Tests share one set of Docker containers (MongoDB, Kafka, Redis, Elasticsearch, S3 stand-in, Mailpit),
which stay running after the build so the next run starts in seconds. Stop them with
`docker rm -f $(docker ps -q --filter label=org.testcontainers.hash)`, or build with
`-Dtestcontainers.reuse=false` to have them removed at the end of each run.

To try the full stack on one machine: `mvn -pl deploy/unit-deployer -am package -DskipTests`, unzip
`deploy/unit-deployer/target/unit-deployer-*.zip` and run `./install.sh ~/foodstack --local`
(no sudo), then open `http://foodstack.localhost:8080/` for the storefront and `/admin/` for the back office.

## Test sign-ins

These are fixed so every developer and tester uses the same ones. They exist only in local runs and
`--local` trial installs; a real install (`--domain`) generates a random administrator password.

| Where | Email | Password | Role |
|---|---|---|---|
| `LocalFoodstackApplication` | `admin@demo.foodstack.local` | `foodstack-demo` | Administrator |
| `LocalFoodstackApplication` | `finance@demo.foodstack.local` | `foodstack-demo` | Finance |
| `LocalFoodstackApplication` | `support@demo.foodstack.local` | `foodstack-demo` | Support |
| `LocalFoodstackApplication` | `customer@demo.foodstack.local` | `foodstack-demo` | Customer |
| `LocalFoodstackApplication` | `spiceroute@demo.foodstack.local` | `foodstack-demo` | Brand owner of Spice Route (both branches) |
| `LocalFoodstackApplication` | `napoli@demo.foodstack.local` | `foodstack-demo` | Branch manager of Napoli Slice (DHA) |
| `LocalFoodstackApplication` | `rider@demo.foodstack.local` | `foodstack-demo` | Operator rider (Bilal Ahmed, motorbike) |
| `LocalFoodstackApplication` | `rider2@demo.foodstack.local` | `foodstack-demo` | Operator rider (Ayesha Noor, scooter) |
| `LocalFoodstackApplication` | `napoli.rider@demo.foodstack.local` | `foodstack-demo` | Napoli Slice's own rider (Usman Tariq) |
| `LocalFoodstackApplication` | `spiceroute.rider@demo.foodstack.local` | `foodstack-demo` | Spice Route's rider (Hamza Iqbal), home branch Gulberg, also rides for DHA Phase 5 |
| `LocalFoodstackApplication` | `admin@local.foodstack.local` | `foodstack-local` | Administrator (first-start account) |
| `./install.sh <folder> --local` | `admin@foodstack.localhost` | `foodstack-local` | Administrator |

Real installs, including `--local` trial installs, ask administrator and finance accounts to set up
an authenticator app at their first sign-in (two-step sign-in, PRD ID-5); keep the recovery codes it
shows. `LocalFoodstackApplication` leaves two-step sign-in optional, so the shared accounts above keep
working for everyone; try it from the account menu.

In a local run, emails (order updates, sign-in codes, password resets) go to Mailpit, whose web page
is printed at start-up ("Emails sent by this run: http://localhost:<port>"); open it to read a sign-in
code. Card payments use the sandbox page, which charges nothing: switch card on in the back office
**Ordering** page, then pay or decline on the test page. A `--local` trial install has no email
server and takes cash only until you fill in `SMTP_*` and `PAYMENT_GATEWAY` in its `.env`
([deployment guide 3.3](docs/deployment-guide.md#33-configure)).

The containers behind `LocalFoodstackApplication` also use fixed credentials: Elasticsearch user
`elastic` / `foodstack-test`, storage access key `foodstack-test` / secret `foodstack-test-secret`;
MongoDB and Redis have no password. Their ports are picked by Docker (`docker ps` shows them).

## Documents

- [Business Requirements (BRD)](docs/BRD.md)
- [Product Requirements (PRD)](docs/PRD.md)
- [Technical Design (TDD)](docs/TDD.md)
- [Architecture and flows](docs/architecture/README.md): diagrams of how the built system works today
- [Deployment Guide](docs/deployment-guide.md)
- [Kubernetes playground](playground/k8s/README.md): FoodStack on a local Kubernetes cluster in any topology, with the datastores in Docker
- [Operator handbook](docs/handbook/README.md): step-by-step setup, day-to-day running and scaling runbooks for operators
- [Manual QA](qa/README.md): test cases for the whole application
- [Testing Strategy](docs/testing-strategy.md)

## License

[Fair Revenue Share License v1](https://ilyaslabs.github.io/docs/licenses/fair-revenue-v1): free to use until your project earns USD 10,000 in total revenue, then 5% of monthly gross revenue is shared with the author.
