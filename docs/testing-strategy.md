# FoodStack: Testing Strategy

| | |
|---|---|
| Status | Draft v1, agreed with ilyas on 2026-10-29 |
| Related | [TDD](TDD.md) section 15.3, [PRD](PRD.md) |

## 1. Principles

1. **Test one class at a time, and mock only the layer directly below it.** The class under test always runs for real.
2. **The lowest layer runs against the real thing.** Code that talks to a database, broker, cache, search engine or storage is tested against a real instance in a container, never against a mock.
3. **Full-application tests live in one place:** the `integration-tests` module. No module contains tests that start the whole application.
4. **Every integration scenario runs twice:** once with the whole application as one unit (monolith), and once with every module as a separate service (microservices). From day one we know the code works both ways.
5. **Few, meaningful integration scenarios.** Breadth comes from fast class-level tests; integration tests prove that the pieces fit together.

## 2. Class-level tests (inside each module)

For a chain `Controller → Service → Repository` (or `ClassA → ClassB → ClassC`):

| Class under test | Runs for real | Mocked | Where | How |
|---|---|---|---|---|
| **Repository / ClassC** (lowest layer, touches infrastructure) | The repository and a real MongoDB | Nothing | `<m>-core` | `@DataMongoTest` with the shared Testcontainers MongoDB |
| **Service / ClassB** | The service and its domain objects | The repository, other modules' `-api` interfaces, the clock, external providers | `<m>-core` | Plain JUnit + Mockito; no Spring context |
| **Controller / ClassA** | The controller plus the MVC stack: routing, validation, JSON, security rules, error mapping | The core service | `<m>-web` | `@WebMvcTest` with `@MockitoBean` for the service |
| **Api implementation** (`OrderApiImpl`) | The class and its mapping, including error translation to `ModuleException` | The core service | `<m>-impl` | Plain JUnit + Mockito |

The same rule applies to every other kind of class:

| Kind of class | Treated as | Runs against |
|---|---|---|
| Outbox relay, Kafka publisher | Lowest layer | Real MongoDB and Kafka containers |
| Kafka consumer | Middle layer | Consumer for real; the service it calls is mocked |
| Elasticsearch adapter | Lowest layer | Real Elasticsearch container |
| S3 storage adapter, image processing | Lowest layer | Real S3-compatible container (RustFS), real image codecs |
| Redis usage (cache, locks, rate limits, revocation) | Lowest layer | Real Redis container |
| External provider adapters (payment gateway, maps, FCM, SMTP) | Lowest layer | WireMock (HTTP providers) or Mailpit (SMTP), playing the provider's real responses, including failures and timeouts |
| Domain objects and value objects (pricing, state machine, money, delivery charge, commission) | Pure logic | Nothing to mock; tested directly with many cases |
| Scheduled jobs | Middle layer | Job for real; services mocked; fixed `Clock` |

Rules:

- Never mock the class under test, value objects, records or `Money`.
- Never mock a repository in its own test, and never use an in-memory fake database.
- A service test that needs more than about five mocks is a signal to split the service.
- Liquibase changelogs are applied before repository tests, so indexes and shard-friendly queries are tested as they will run.
- Repository tests run on a **two-shard MongoDB** container (started once per run) with every collection sharded on its planned key, so a query or index that would not work on a sharded cluster fails here ([TDD 6.3](TDD.md#63-designed-for-sharding)).

### Contract snapshots (fast, inside modules)

- **Events:** each event record in `<m>-api` has a JSON snapshot test. A change that removes or renames a field fails the build; additive changes update the snapshot.
- **Internal module APIs:** `ModuleRules.verify("io.github.ilyaslabs.foodstack")` checks every `@ModuleApi` interface (no overloads, `@Param` everywhere, no cross-module `-impl` or `-web` use).
- **Architecture:** ArchUnit rules enforce the layer rule above (controllers only in `-web`, `-impl` and `-web` use only `-core` application services, repositories only used inside their own `-core`, no module depends on another module's `-core`, `-impl` or `-web`).

## 3. Integration tests (`integration-tests` module)

### 3.1 Where and what

```
integration-tests/
├─ pom.xml                       depends on foodstack-server (all modules)
└─ src/test/java/…/it/
   ├─ support/                   topology launcher, REST client with path routing, fake payment gateway, test users
   └─ scenarios/                 one class per business journey
```

- The only module that starts the whole application.
- Talks to the application **only through the public REST API** (REST Assured), exactly like the web apps, plus the fake external providers.
- Keeps the scenario list **short**: one test per business journey, not per edge case. Edge cases belong in class-level tests.

### 3.2 Every scenario runs in both topologies

Each scenario class is annotated `@BothTopologies`, which runs it twice:

| Run | What starts | How |
|---|---|---|
| `MONOLITH` | One application context, every module `local` | The production configuration of the unit deployer |
| `MICROSERVICES` | One application context **per module** (14), each with only its own module `local`, every other module `http` to the owning context on a random internal port | The same configuration the Helm chart generates for `topology: microservices` |

- Both runs share the same containers (MongoDB, Kafka, Redis, RustFS, and from later milestones Elasticsearch, Mailpit and WireMock) but use fresh databases, topics and buckets, so they never see each other's data.
- In the microservices run, the test client routes each REST path to the context that owns it, using the same module → path map the gateway uses. This also proves the routing map is complete.
- Asynchronous outcomes (notifications, ledger entries, search indexing) are awaited with Awaitility, with a short timeout.
- Both runs must pass on every pull request.

### 3.3 Scenario list (v1)

| # | Journey |
|---|---|
| 1 | Customer signs up and logs in with an email code; token works on every module; revoked token is rejected everywhere |
| 2 | Admin onboards a store; owner sets hours, zones and menu with images; store appears in search for an address inside its zone and not outside |
| 3 | Cash order, self-delivery store: place, accept, prepare, store rider delivers; ledger and notifications correct |
| 4 | Card order on the hosted payment page (fake gateway), operator-delivery store: payment webhook, dispatch to an operator rider, live tracking events, delivery; operator receives the delivery charge and rider pay is posted |
| 5 | Pickup order collected by the customer |
| 6 | Store rejects, and customer cancels before acceptance: refund issued, coupon released |
| 7 | Operator coupon restricted to operator-collected card payments: applies to an operator-collected order, refused for a store-collected one |
| 8 | Store direct payment collection: payment lands on the store account, receivable recorded, credit limit suspends direct collection, settlement restores it |
| 9 | Temporarily closed store takes no orders until reopened |
| 10 | Customer reviews store and rider; rating appears in search |
| 11 | Payout report for a period nets store payables and receivables to the cent |
| 12 | Data export and account deletion request |

## 4. Front-end tests

| Level | Tooling | Rule |
|---|---|---|
| Component | Vitest + Angular Testing Library | Component runs for real; services and API client mocked (same rule as the backend) |
| Services and stores | Vitest | Real service; HTTP mocked with the generated API client's test helpers |
| End-to-end | Playwright | The PRD journeys against the unit deployer stack on every PR (smoke) and nightly (full) |
| Visual | Playwright screenshots | Key screens in LTR and RTL, light and dark |
| Accessibility | axe in Playwright | No serious or critical violations |

## 5. API compatibility

- The merged OpenAPI document is generated in `integration-tests` and compared with the committed snapshot. A breaking change in `/api/v1` fails the build; an additive change updates the snapshot in the same pull request.

## 6. Performance

- **Nightly:** k6 smoke run against the monolith (few minutes, catches big regressions).
- **Before each release:** full k6 load test at NFR targets on the Kubernetes `split` and `microservices` topologies, with skewed data (a few very busy stores and customers).

## 7. Test data

- Each module publishes **builders** for its own objects as test fixtures (Maven `test-jar` of `<m>-core`), for example `anOrder().paid().withCoupon("SAVE10").build()`.
- Instancio fills fields a test does not care about, so tests state only what matters.
- Integration scenarios create data through the public API, never by writing to databases directly.
- Fixed `Clock` and seeded randomness everywhere, so tests are repeatable.

## 8. Containers

- One shared set of containers per test run (singleton containers), reused between local runs with Testcontainers reuse for speed.
- Isolation by name, not by container: each test class gets its own database names (`test_<random>_<module>`), Kafka topic prefix, Redis key prefix and bucket names.
- Docker is the only prerequisite to run every test locally.

## 9. Coverage and quality gates

| Gate | Rule |
|---|---|
| Changed-line coverage | At least 80% of lines changed in a pull request are covered (JaCoCo) |
| Mutation testing | PIT on money-critical code (pricing, delivery charges, commission, promotions, ledger) with at least 70% mutants killed |
| Flaky tests | A test that fails intermittently is fixed or deleted within a week; never retried automatically to hide it |
| Static checks | Formatter, compiler warnings as errors, ArchUnit, `ModuleRules.verify` |

## 10. Style

- JUnit 5 and AssertJ; Mockito for mocks.
- Layout: `// given`, `// when`, `// then`; one behaviour per test.
- Names: `@DisplayName` as a sentence ("rejects a coupon that is restricted to operator-collected payments").
- Test class names: `<Class>Test` for class-level tests, `<Journey>Scenario` for integration scenarios.
- Tests sit next to the code they test, in the same module.

## 11. What runs where

| When | Runs |
|---|---|
| Every pull request | All class-level tests, contract snapshots, architecture checks, integration scenarios in **both** topologies, OpenAPI diff, front-end component tests, Playwright smoke, coverage and mutation gates |
| Nightly | Everything above, Playwright full suite and visual tests, k6 smoke, Helm installs of every topology on kind |
| Before each release | Everything above, full k6 load test, security scans |
