# FoodStack documentation: where to start

Pick the line that matches what you want to do. You never need to read everything.

## I want to run FoodStack

| I want to… | Read | You get |
|---|---|---|
| Try it on my laptop in ten minutes | [deploy/compose, "Try it on your own computer"](../deploy/compose/README.md#try-it-on-your-own-computer) | Everything running on `http://localhost:8080`, no accounts needed |
| Launch a marketplace, step by step, without being an engineer | [Operator handbook](handbook/README.md), guides 10 to 19 | A live marketplace on one server, with your domain, buckets, maps, email and payments |
| Install on one server, as an engineer | [deploy/compose](../deploy/compose/README.md) | Docker Compose with every setting in one `config/application.yml` |
| Run modules as separate services, without Kubernetes | [deploy/services](../deploy/services/README.md) | One ready-to-fill settings file per module, and the proxy in front |
| Run on Kubernetes | [Kubernetes guide](kubernetes-guide.md) | The Helm chart: every step and every setting |
| Move an install that already runs (one server to several, to Kubernetes, to split modules) | [Migration guide](migration-guide.md) | The steps for each move, and what must move with you |
| Keep it running: backups, upgrades, certificates, health | [Operator handbook](handbook/README.md#running-it-day-to-day), guides 20 to 23 | Day-to-day routines |
| Grow it as orders grow | [Knowing when to grow](handbook/30-when-to-grow.md), then the runbooks 40 to 49 | Which signal calls for which step |

The [Deployment guide](deployment-guide.md) is the short version of all of these, for engineers.

## I want to know which part needs what

[Architecture 1.0, What each service needs](architecture/01-big-picture.md#10-what-each-service-needs):
one diagram and one table showing, for each of the 15 modules, its database, the datastores it
uses (MongoDB, Redis, Kafka, Elasticsearch) and the outside services it depends on (buckets, maps,
email, push, card payments, social sign-in).

## I want to change the code

| I want to… | Read |
|---|---|
| Build and run it locally | [Root README](../README.md#build-and-run) |
| See how a flow works (ordering, delivery, payments, sign-in…) | [Architecture](architecture/README.md): Mermaid diagrams traced from the code |
| Know why it is built this way | [Technical design (TDD)](TDD.md) |
| Know what it must do | [Product requirements (PRD)](PRD.md) and [Business requirements (BRD)](BRD.md) |
| Write or run tests | [Testing strategy](testing-strategy.md), and the manual cases in [qa/](../qa/README.md) |
