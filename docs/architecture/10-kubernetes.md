# 10. Kubernetes, sign-in security and failed events (M5)

[Back to index](README.md)

What the Helm chart installs and how a request crosses it, then the two sign-in flows M5 added (two-step and Google or Apple), the failed-events replay, and where metrics, logs and traces go. Traced from `k8s/helm/foodstack`, `k8s/helm/foodstack-observability`, the `identity` and `settings` modules and `platform-kafka`.

## 10.1 What the chart installs

Shown for `topology: split`. `monolith` has one `core` Deployment, `microservices` one per module, and `custom` whatever `server.deployments` lists. The chart writes the Module-Switch settings, the routes and the mesh policies for each one.

```mermaid
flowchart TB
    U["Browsers and apps"] -->|"HTTPS"| GW
    subgraph NS["Namespace foodstack (every pod in Istio ambient: ztunnel mTLS)"]
        GW["Public Gateway (Envoy, autoscaled)<br/>TLS · route by path prefix<br/>token check (RequestAuthentication)<br/>per-IP limits"]
        RL["Envoy rate-limit service ×2<br/>counters in Redis"]
        IGW["Internal Gateway (ClusterIP)<br/>/oauth2/jwks for the token check<br/>API for web server-side rendering"]
        WP["Waypoint<br/>per-request balancing between pods"]
        WEB["web (SSR)"]
        BO["backoffice (nginx)"]
        subgraph SRV["Server Deployments: one image"]
            CORE["core<br/>identity, settings, media, geo, store,<br/>cart, promotion, payment, rider,<br/>notification, ledger, review"]
            ORD["order"]
            DIS["dispatch"]
            SEA["search"]
        end
        MIG["Migrations Job<br/>(hook, before pods start)"]
        SEC[("Secret foodstack-secrets<br/>signing keys, personal-data key,<br/>internal token, first password")]
    end
    GW --- RL
    GW -->|"/"| WEB
    GW -->|"/admin/"| BO
    GW -->|"/api/v1/orders…"| ORD
    GW -->|"/api/v1/dispatch…, /deliveries…"| DIS
    GW -->|"/api/v1/search…"| SEA
    GW -->|"every other /api prefix"| CORE
    WEB --> IGW
    IGW --> CORE
    CORE <-->|"port 8081, Module-Switch,<br/>via the waypoint"| ORD
    CORE <--> DIS
    CORE <--> SEA
    ORD <--> DIS
    SEC -.-> SRV
    MIG -.-> DS[("MongoDB · Kafka · Redis · Elasticsearch · S3<br/>(managed, or in the cluster with values-dev.yaml)")]
    SRV --> DS
```

| Port on a server pod | Who may connect (AuthorizationPolicy, enforced by ztunnel) |
|---|---|
| 8080 public API | The two gateways, the web pod, other server pods, the waypoint |
| 8081 Module-Switch | Server pods and the waypoint only (plus the internal token) |
| 8082 health and metrics | Any pod in the mesh (Prometheus joins the mesh to scrape it) |

## 10.2 A signed-in request at the edge

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant G as Public Gateway (Envoy)
    participant R as Rate-limit service
    participant I as Internal Gateway
    participant S as Server pod (owner of the path)
    B->>G: GET /api/v1/orders/mine, Bearer access token
    G->>R: count for this client IP (api, and sign-in or orders where they apply)
    R-->>G: under the limit (fails open if the service is down)
    Note over G,I: Envoy fetches /oauth2/jwks through the internal gateway and keeps retrying<br/>(istiod with PILOT_JWT_ENABLE_REMOTE_JWKS=envoy)
    G->>G: token signature and expiry against the JWKS (no token passes)
    alt forged or expired token
        G-->>B: 401 Jwt verification fails
    else valid or no token
        G->>S: request (mTLS through ztunnel)
        S->>S: checks the token again, the revocation floor in Redis and the permission
        S-->>B: 200 with the orders
    end
```

## 10.3 Two-step sign-in with an authenticator app

Required for ADMIN and FINANCE (`foodstack.identity.totp.required-roles`); other back-office users may turn it on; customers do not have it. Every first step (password, emailed code, password reset, Google or Apple) ends the same way.

```mermaid
sequenceDiagram
    autonumber
    participant A as Back office (sign-in card)
    participant AU as identity: AuthController
    participant SS as SessionService
    participant TF as TwoFactorService
    participant CT as ChallengeTokens
    A->>AU: POST /auth/login {email, password, client}
    AU->>SS: signInWithPassword
    SS->>TF: secondStep(user)
    alt two-step on
        TF-->>SS: TOTP
        SS->>CT: issue(user, TOTP), HS256, 5 min, audience foodstack-sign-in
        AU-->>A: 200 {next: TOTP, challenge}
        A->>AU: POST /auth/two-step {challenge, code}
        AU->>CT: verify(challenge, step TOTP)
        AU->>TF: verify(user, code): 6 digits, step before or after allowed,<br/>newer than the last used step (atomic), or a recovery code (used once)
        TF-->>AU: ok (wrong tries are limited per account)
        AU-->>A: access token and refresh cookie
    else role requires it, not set up yet
        TF-->>SS: TOTP_ENROLL
        AU-->>A: 200 {next: TOTP_ENROLL, challenge}
        A->>AU: POST /auth/two-step/enroll {challenge}
        AU-->>A: secret and otpauth:// link (QR drawn in the page)
        A->>AU: POST /auth/two-step/confirm {challenge, code}
        AU-->>A: tokens and 10 recovery codes, shown once
    else not required and not on
        AU-->>A: access token and refresh cookie
    end
```

Later, on **Two-step sign-in** in the account menu, the user can get new recovery codes or turn it off (where the role allows), each with a current code. An administrator can reset it on the Users page, which ends all of that user's sign-ins.

## 10.4 Continue with Google or Apple

```mermaid
sequenceDiagram
    autonumber
    participant W as Customer app
    participant P as Google or Apple (their script, popup)
    participant AU as identity: AuthController
    participant ST as SocialTokens
    participant US as UserService
    W->>AU: GET /auth/providers (client ids from the Sign-in options page)
    W->>P: sign in with the provider
    P-->>W: ID token (and Apple's name, first time only)
    W->>AU: POST /auth/social {provider, idToken, client}
    AU->>ST: verify: RS256 against the provider's keys, issuer,<br/>audience among the client ids, expiry, verified email
    AU->>US: socialAccount(provider, subject, email)
    alt provider account already linked
        US-->>AU: that user
    else an account has this email
        US->>US: link it. If nobody ever proved the address (emailVerifiedAt empty),<br/>clear its password and end its sign-ins
        US-->>AU: that user
    else new
        US->>US: create a customer, email marked proven
        US-->>AU: new user
    end
    AU-->>W: tokens (or a second step, as in 10.3)
```

## 10.5 An event that keeps failing, and its replay

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka topic foodstack.ledger.events
    participant N as notification listener (group foodstack-notification)
    participant P as payment listener (group foodstack-payment)
    participant D as foodstack.ledger.events.dlt
    participant SL as settings: DeadLetterListener
    participant DB as settings_dead_letters
    participant BO as Back office: Failed events
    K->>N: event
    N->>N: fails 3 times (growing pause)
    N->>D: event, error, original topic and group in headers
    K->>P: same event, handled
    D->>SL: every .dlt topic
    SL->>DB: entry OPEN (event encrypted, kept 30 days)
    BO->>DB: GET /api/v1/settings/dead-letters (settings:manage)
    BO->>SL: POST /{id}/replay
    SL->>K: the same event, header foodstack-replay-for = foodstack-notification
    K->>N: handled again (only this group)
    K->>P: skipped by the replay filter
    SL->>DB: REPLAYED (if it fails again, a new OPEN entry appears)
```

## 10.6 Metrics, logs and traces

```mermaid
flowchart LR
    subgraph FS["Namespace foodstack"]
        S["Server pods<br/>:8082/actuator/prometheus<br/>ECS JSON logs with trace id"]
    end
    subgraph OBS["Namespace foodstack-observability"]
        C["OTel Collector (one per node)<br/>reads /var/log/pods, takes OTLP"]
        PR["Prometheus (in the mesh)<br/>8 alert rules"]
        AM["Alertmanager (optional)"]
        L["Loki"]
        T["Tempo"]
        G["Grafana<br/>3 FoodStack dashboards"]
    end
    S -- "spans, OTLP 4318" --> C
    S -. "log files on the node" .-> C
    C --> L
    C --> T
    PR -- "scrapes pods annotated prometheus.io/scrape" --> S
    PR --> AM
    G --> PR
    G --> L
    G --> T
```
