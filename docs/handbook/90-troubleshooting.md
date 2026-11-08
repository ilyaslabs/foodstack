# 90 Troubleshooting

[Back to the handbook](README.md)

Find what you see in the left column. Each guide also has its own table at the end; this page gathers the common ones and points to the right guide. Commands are for one server, run in `/opt/foodstack`; on Kubernetes the same checks are `kubectl -n foodstack get pods` and `kubectl -n foodstack logs deploy/foodstack-core`.

## First, three checks

1. `./status.sh`: is every service `running` and `(healthy)`, and the disk below 80 %?
2. `./logs.sh server` for a minute: are there lines with `ERROR`?
3. **Failed events** and **Email log** in the back office: is something waiting there?

Most problems show in one of these. When you ask the FoodStack team for help, send all three.

## The site

| What you see | Likely cause | What to do |
|---|---|---|
| The browser cannot reach the site at all | The front door is down, the server is off, or DNS changed | `./status.sh`; is `proxy` running? Is the server on in the provider's panel? `nslookup example.com` shows your server's address ([guide 11](11-domain-and-dns.md)) |
| "Your connection is not private" | The certificate did not arrive or renew | [Guide 22](22-certificates.md) |
| The page loads but says the service is unavailable, or shows an error everywhere | The FoodStack server is down or restarting | `./status.sh`, then `./logs.sh server`. `./restart.sh server` brings it back in about a minute |
| Everything is slow at busy hours | The server is out of power | [Guide 30](30-when-to-grow.md) |
| Everything is slow all the time, CPU is low | A service outside FoodStack is slow (maps, email), or the disk is full | `./status.sh` for the disk; `./logs.sh server` for timeouts |
| The disk is full | Backups and logs | Delete old backups ([guide 20](20-backups.md)), then make the disk bigger |

## Signing in

| What you see | Likely cause | What to do |
|---|---|---|
| "That email and password do not match." | Wrong password, or the wrong account | "Forgot password?" on the sign-in page (needs email, [guide 15](15-email.md)) |
| The authenticator code is refused | The phone's clock is off | Automatic date and time on the phone ([guide 17](17-first-sign-in.md)) |
| A staff member lost their phone and codes | | **Users**, their row, **Reset two-step sign-in**, after checking it is them |
| Signed out again and again on the customer app | The app and the API are on different sites, or the cookie is blocked | Keep `example.com` and `api.example.com` on the same domain; `FOODSTACK_REFRESH_COOKIE_SECURE=true` in `.env` with HTTPS |
| "Too many tries" | Ten wrong passwords in 15 minutes | Wait 15 minutes |
| No sign-in code email | Email not set up, or in spam | **Email log** ([guide 15](15-email.md)) |

## Orders and restaurants

| What you see | Likely cause | What to do |
|---|---|---|
| Orders are rejected by themselves | Nobody accepted within **Time to accept** | The restaurant keeps **Live orders** open with **Turn on sound** ([guide 19](19-first-store.md)) |
| A restaurant does not appear for customers | Not live, closed now, or the customer is outside its zones | The branch page's status and readiness box, **Hours**, and **Delivery** ([guide 19](19-first-store.md)) |
| Card orders stay "Waiting for payment" | Stripe's reports do not reach FoodStack | Webhook address and secret ([guide 16](16-card-payments.md)) |
| Checkout shows no card option | Card is off, or Stripe is not set up | **Ordering**, and `PAYMENT_GATEWAY=stripe` ([guide 16](16-card-payments.md)) |
| Totals look wrong | A fee, tax or delivery price setting | **Ordering** and **Delivery pricing** ([guide 18](18-branding-and-rules.md)) |
| No rider takes deliveries | No riders online nearby, or the search radius is small | **Dispatch** lists them; **Rider pay** sets how far to look ([guide 18](18-branding-and-rules.md)) |
| A restaurant was told late, or a customer email came late | Events are waiting | **Failed events**; on Kubernetes, the **FoodStack events** dashboard ([guide 23](23-health-and-dashboards.md)) |
| Search shows old results or none | The search index is behind or empty | Restaurants must be live with a delivery area or pickup. Changes arrive within seconds and a full re-read runs every 24 hours; if a change is stuck, look in **Failed events** |

## Email, maps and photos

| What you see | Likely cause | What to do |
|---|---|---|
| **Email log**: "Email is not set up (SMTP_HOST)" | `SMTP_HOST` or `SMTP_FROM` empty, or not restarted | [Guide 15](15-email.md) |
| **Email log**: "Authentication failed" or "timed out" | Wrong SMTP details, or port 587 blocked by the provider | [Guide 15](15-email.md) |
| "Address search is not available right now." | The search service cannot be reached | [Guide 13](13-maps.md) |
| The map is grey | Tile address and origin do not match | [Guide 13](13-maps.md) |
| A photo upload is refused | Over 10 MB, or not PNG, JPEG, WebP or SVG | Make it smaller |
| Photos broken after moving them to your buckets or a CDN | Stored image addresses still name the old place | [Guide 12](12-storage.md#move-photos-from-this-server), [runbook 49](49-cdn.md) step 4 |
| The server does not start: "Storage check failed: …" | A bucket is missing, not public, not writable, or has no CORS rule for your two sites | [Guide 12](12-storage.md#if-something-goes-wrong) |
| The server does not start: "… needs GOOGLE_MAPS_API_KEY" | Google is chosen for maps but the key is missing | [Guide 13](13-maps.md) |

## The server and its services

| What you see in `./status.sh` or the logs | Likely cause | What to do |
|---|---|---|
| `server` restarts again and again | A wrong setting in `.env` | `./logs.sh server`: the first `ERROR` names it. Undo your last change to `.env` |
| `elasticsearch` restarts | Too little memory | [Guide 23](23-health-and-dashboards.md) |
| `mongodb` is not healthy | Disk full, or the volume damaged | Free disk space, `./restart.sh mongodb`; if it does not come back, restore ([guide 20](20-backups.md)) |
| `kafka` is not healthy | Disk full, or it was stopped while writing | Free disk space, `./restart.sh kafka`. Events wait in the database meanwhile; nothing is lost |
| After an upgrade, something is broken | | `./upgrade.sh --rollback` ([guide 21](21-upgrades.md)) |

## Kubernetes

| What you see | What to do |
|---|---|
| Every signed-in request answers 401 "Jwt verification fails" | istiod needs `PILOT_JWT_ENABLE_REMOTE_JWKS=envoy` ([deployment guide 4.1](../deployment-guide.md#41-cluster-prerequisites)); restarting istiod fixes it until then |
| 503 "remote connection failure" after a machine restarted | Pods started before Istio was ready: `kubectl -n foodstack rollout restart deploy,statefulset` |
| Pods `Pending` | The cluster is full ([runbook 41](41-add-server-replicas.md)) |
| Pods `CreateContainerConfigError` | A Secret is missing: `kubectl -n foodstack describe pod <name>` names it |
| Prometheus shows the server pods as down ("EOF") | Keep the observability chart's `ambient: true` ([deployment guide 4.9](../deployment-guide.md#49-observability)) |
| An upgrade stops at the migrations job | `kubectl -n foodstack logs -l app.kubernetes.io/name=foodstack-migrations`; `helm rollback foodstack -n foodstack` ([guide 21](21-upgrades.md)) |
