# FoodStack

FoodStack is an open-source food ordering and delivery platform. An operator can launch their own branded delivery marketplace (customer app, back office for admins, stores and riders, REST API, Kubernetes deployment) without first building a tech team.

The backend is a Spring Boot 4 / Java 25 modular monolith built on [Module-Switch](https://github.com/ilyaslabs/module-switch), so any module can move into its own service by changing configuration only.

## Status

Milestone M0 (foundation): the build, the `identity`, `settings` and `media` modules, the server, the
customer web app and back office, the integration journeys in both topologies, Docker images, CI,
and the single-server unit deployer. See [TDD section 21](docs/TDD.md#21-m0-implementation-notes)
for what M0 does differently from the design and what follows.

## Repository

| Path | What |
|---|---|
| `platform/` | Shared libraries: ids and money, per-module MongoDB with Liquibase and the outbox, Kafka relay, security, web errors, test containers |
| `modules/<module>/` | One module in four jars: `-api` (contract), `-core` (logic and data), `-impl` (module API), `-web` (REST) |
| `app/foodstack-server` | The server: every module, switched local, HTTP or off by configuration |
| `integration-tests/` | Business journeys through the REST API, each run as a monolith and as one service per module |
| `frontend/` | Angular workspace: `web` (SSR customer app), `backoffice`, shared libraries |
| `deploy/unit-deployer/` | Docker Compose, Caddy and scripts for one server, packaged as `unit-deployer-<version>.zip` |

## Build and run

Prerequisites: Java 25, Maven, Docker; Node 24 and pnpm for the front-end.

```bash
mvn test                                  # every test, including both integration topologies
mvn -pl app/foodstack-server -am install -DskipTests
# Run the server with containers: LocalFoodstackApplication (app/foodstack-server/src/test), then
cd frontend && pnpm install && pnpm start:backoffice   # http://localhost:4300/admin/
```

Sign in locally as `admin@local.foodstack.local` / `foodstack-local`. To try the full stack on one
machine: `mvn -pl deploy/unit-deployer -am package -DskipTests`, unzip
`deploy/unit-deployer/target/unit-deployer-*.zip` and run
`sudo ./install.sh /opt/foodstack --domain localhost --http --http-port 8088`.

## Documents

- [Business Requirements (BRD)](docs/BRD.md)
- [Product Requirements (PRD)](docs/PRD.md)
- [Technical Design (TDD)](docs/TDD.md)
- [Deployment Guide](docs/deployment-guide.md)
- [Testing Strategy](docs/testing-strategy.md)

## License

[Fair Revenue Share License v1](https://ilyaslabs.github.io/docs/licenses/fair-revenue-v1): free to use until your project earns USD 10,000 in total revenue, then 5% of monthly gross revenue is shared with the author.
