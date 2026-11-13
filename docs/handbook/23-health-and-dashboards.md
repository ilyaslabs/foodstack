# 23 Health checks and dashboards

[Back to the handbook](README.md)

A short routine that catches problems before customers or restaurants call you: a two-minute morning check, an outside monitor that emails you when the site is down, and (on Kubernetes) dashboards and alerts.

## What you need

- FoodStack installed, and signed in to the back office as an administrator.
- For the outside monitor: a free account with an uptime service (UptimeRobot, Better Stack, Uptime Kuma or similar).
- 10 minutes to set up, then 2 minutes a day.

## Steps

### Every morning (2 minutes)

1. **Is everything running?** On the server:
   ```bash
   cd ~/foodstack
   docker compose ps
   df -h /
   ```
   Every service should say `Up`, and those with a health check `(healthy)`. `df` shows how full the disk is: act above 80 %.
2. **Did any event fail?** In the back office, **Failed events**. FoodStack passes work between its parts as events (an order was placed, so tell the restaurant). If one keeps failing it waits here instead of being lost. "No failed events. Everything was handled." is what you want. If there are some, see [Failed events](#failed-events) below.
3. **Did the emails go out?** **Email log**: yesterday's emails show as sent. Failed ones show the error and a **Send again** button ([guide 15](15-email.md)).
4. **Are orders flowing?** **Orders** shows yesterday's orders. Many **Rejected** orders with "did not answer in time" mean a restaurant is not watching **Live orders**. If you run riders, **Dispatch** lists deliveries no rider took.
5. **Backups**: last night's file is in `ls -l backups` ([guide 20](20-backups.md)).

### An outside monitor (once)

6. In your uptime service, add two checks, every 1 to 5 minutes, that email or text you when they fail:
   - `https://api.example.com/actuator/health/readiness`: expects the word `UP`. It fails when the FoodStack server is down or restarting.
   - `https://example.com/`: expects status 200. It fails when the front door or the customer app is down.

   The health check stays `UP` while the database is briefly unreachable, because FoodStack reconnects by itself and restarting it would not help. A database problem shows up as errors in the apps and in `docker compose logs server`.

### Reading the logs

7. To see what a part of FoodStack is doing:
   ```bash
   docker compose logs -f --tail 200 server
   ```
   It shows the last 200 lines and then new ones as they come; Ctrl+C stops. Other names: `proxy` (the front door and certificates), `mongodb`, `kafka`, `redis`, `elasticsearch`, `web`, `backoffice`. Lines with `ERROR` or `WARN` are the interesting ones. When you ask the FoodStack team for help, send the lines around the problem.

### Failed events

8. Open the event (**Show event**) and read the error. Usually something outside FoodStack was wrong for a while: the mail service, the database, a full disk.
9. Fix the cause, then click **Replay**. The event goes again to the one part that failed (the others already handled it), and the row shows **Replayed**. If it fails again, a new row appears with the new error.
10. **Dismiss** only an event you are sure no longer matters (for example about an order you already sorted out by hand). Failed events are kept 30 days.

### Dashboards and alerts (Kubernetes)

On Kubernetes, the observability chart gives you dashboards and alerts ([deployment guide 4.9](../deployment-guide.md#49-observability) installs it).

11. Open Grafana from your computer:
    ```bash
    kubectl -n foodstack-observability port-forward svc/foodstack-grafana 3000:3000
    ```
    Open `http://localhost:3000`, sign in as `admin` with the password from `kubectl -n foodstack-observability get secret foodstack-grafana -o jsonpath='{.data.admin-password}' | base64 -d`.
12. The **FoodStack** folder has three dashboards:

    | Dashboard | Look at | Worry when |
    |---|---|---|
    | FoodStack overview | Requests, errors and response time for each part, memory | Errors above 1 %, or response time (95th percentile) above half a second for long |
    | FoodStack orders and delivery | Orders by status, time to find a rider, pickup to door | Time to find a rider rising at lunch and dinner: you need more riders, or [guide 30](30-when-to-grow.md) |
    | FoodStack events | Events waiting to be sent, how far behind each reader is, failures | The backlog or the lag keeps growing instead of going back to zero |

13. Alerts are listed in Grafana under **Alerting**. To have them emailed, ask whoever set up the cluster to switch on Alertmanager with your email ([deployment guide 4.9](../deployment-guide.md#49-observability)).

## Check it worked

- `docker compose ps` shows everything healthy.
- Your uptime service shows both checks green. Test it once: `docker compose stop server` for two minutes, and you get the alert; `docker compose up -d` brings it back.

## Undo

Nothing here changes FoodStack. Delete the checks in the uptime service to stop its emails.

## If something goes wrong

| What you see | What to do |
|---|---|
| A service is `Restarting` or `Exited` in `docker compose ps` | `docker compose logs <service>` says why. Then `docker compose restart <service>` |
| `elasticsearch` keeps restarting | Usually too little memory: FoodStack needs at least 16 GB ([guide 10](10-server.md)). On a smaller server, lower `ES_HEAP` in `.env` (for example `ES_HEAP=512m`) and `docker compose up -d` |
| Disk use above 80 % | Delete old backups (`ls backups`, then `rm backups/<old file>`), keep fewer days ([guide 20](20-backups.md)), or make the disk bigger in your provider's control panel |
| Many failed events with the same error | One cause: fix it, then replay them one by one |
| The site is slow at busy hours | [Knowing when to grow](30-when-to-grow.md) |
