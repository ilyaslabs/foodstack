# 13 Maps and addresses

[Back to the handbook](README.md)

FoodStack needs three map services. A real install does not start without the first two, because OpenStreetMap's free public servers allow only light use and would block a busy marketplace. They are used only by the trial on your own computer.

| Service | What it does in FoodStack | Your choices |
|---|---|---|
| Address search (geocoding) | Customers type their area or street; staff type a restaurant's address | Google, or any Nominatim-compatible service (hosted or your own) |
| Map tiles | The map pictures in the back office (drawing delivery zones) and in live delivery tracking | Any tile service with a `{z}/{x}/{y}` address (hosted or your own) |
| Road distances (routing) | Delivery charges by distance, rider offers, arrival times | Optional. Google, your own OSRM server, or none (straight-line distance times a road factor) |

Do this guide before you install ([guide 14](14-install.md)). It adds lines to the settings file you started in [guide 12](12-storage.md).

## What you need

- The settings file `~/foodstack-settings.env` from [guide 12](12-storage.md).
- The two-letter code of your country, such as `pk` or `ae`. Address search shows only places in it.
- One of these for address search and road distances:
  - **Google Maps Platform**: an account with billing turned on (Google gives a monthly free allowance, then charges per request). Simplest, and good address coverage in most countries.
  - **A hosted Nominatim provider**: several companies sell a Nominatim-compatible search API per request, some with free tiers. Their search URL and key.
  - **Your own servers**: Nominatim and OSRM for your country, each on its own machine with your country's map data. A technical project.
- A **map tiles** provider and its tile URL with your key in it, such as `https://tiles.example-provider.com/{z}/{x}/{y}.png?key=...`, and the credit line it asks you to show. Google's own map pictures cannot be used this way, so you need a tile provider even with Google.

## Steps

### Google (address search and road distances)

1. Sign in to `https://console.cloud.google.com/`, create a project named `foodstack`, and turn on billing for it.
2. Go to **APIs & Services**, **Library**, and enable **Geocoding API** and **Routes API**.
3. Go to **APIs & Services**, **Credentials**, **Create credentials**, **API key**. Open the new key and:
   - under **API restrictions**, choose **Restrict key** and tick only **Geocoding API** and **Routes API**;
   - under **Application restrictions**, choose **IP addresses** and enter your server's public address (from [guide 10](10-server.md)).

   Save, and copy the key.
4. Optional but wise: in **Billing**, **Budgets & alerts**, set a monthly budget so you get an email before costs grow.
5. Your lines:
   ```
   GEOCODING_PROVIDER=google
   ROUTING_PROVIDER=google
   GOOGLE_MAPS_API_KEY=<the key>
   ```
   Leave out `ROUTING_PROVIDER=google` if you want only address search from Google (each delivery quote then costs no Google request, and distances are estimated).

### A Nominatim-compatible service (address search)

5. Your lines, with your provider's search URL (the part before `/search`) and key as the provider shows it:
   ```
   GEOCODING_PROVIDER=nominatim
   GEOCODING_URL=<https://search.example-provider.com/?key=...>
   ```
6. If you run OSRM yourself, add:
   ```
   ROUTING_PROVIDER=osrm
   ROUTING_URL=<http://your-osrm-server:5000>
   ```

### Every install: country and map tiles

7. Add to the settings file:
   ```bash
   nano ~/foodstack-settings.env
   ```
   ```
   GEOCODING_COUNTRY_CODES=<your country code, such as pk>
   MAP_TILE_URL=<https://tiles.example-provider.com/{z}/{x}/{y}.png?key=...>
   MAP_ATTRIBUTION=<© the provider © OpenStreetMap contributors>
   ```
   plus the lines from the part above that you chose. Save with Ctrl+O, Enter, and leave with Ctrl+X.

All addresses must start with `https://` (except your own OSRM server inside your network). The installer refuses `nominatim.openstreetmap.org` and `tile.openstreetmap.org`.

**On Kubernetes** ([guide 40](40-move-to-kubernetes.md)) the same choices go in your values file instead:

```yaml
maps:
  geocoding: { provider: google, countryCodes: pk }   # or provider: nominatim, url: https://...
  routing: { provider: google }                       # or osrm with url, or none
  tiles: { url: "https://tiles.example-provider.com/{z}/{x}/{y}.png?key=...", attribution: "© the provider © OpenStreetMap contributors" }
server:
  envFromSecrets: [foodstack-maps]                    # only with google
```

With Google, first keep the key in a Secret: `kubectl -n foodstack create secret generic foodstack-maps --from-literal=GOOGLE_MAPS_API_KEY=<the key>`.

## Check it worked

After you install:

- In the customer app, type part of a street name in **Search your area or street**: suggestions appear, only in your country.
- In the back office, open a restaurant's **Delivery** tab: the map shows your provider's tiles and its credit in the corner.
- With Google or OSRM routing: a delivery quote on a store page shows a distance that follows the roads.

## Change it later

1. Open the settings:
   ```bash
   cd /opt/foodstack
   nano .env
   ```
2. Change the `GEOCODING_*`, `GOOGLE_MAPS_API_KEY`, `MAP_*` or `ROUTING_*` lines. If you change the tile host, also set `MAP_TILE_ORIGIN` to the `https://host` part of `MAP_TILE_URL`, or the back office refuses to load the tiles.
3. Restart everything that uses maps:
   ```bash
   ./restart.sh server
   ./restart.sh web
   ./restart.sh backoffice
   ```

## Undo

Nothing on your server changes until you install. To undo a later change, put back the old values in `.env` and restart the same three services.

## If something goes wrong

| Problem | What to do |
|---|---|
| The installer says "… needs GOOGLE_MAPS_API_KEY" | You chose `google` but left the key out. Add `GOOGLE_MAPS_API_KEY` |
| The installer refuses the OpenStreetMap servers | Use a provider you are allowed to use at your volume (see "What you need") |
| "Address search is not available right now. Use your location instead." | The search service cannot be reached, or the key is wrong or not allowed for this server's address. `./logs.sh server` shows the error (Google: `REQUEST_DENIED` means the key, its restrictions or billing) |
| The map is grey | The tile key is wrong, or `MAP_TILE_ORIGIN` does not match the host of `MAP_TILE_URL`. In the browser, F12 > Console shows the blocked address |
| Delivery quotes stopped working after turning on routing | The routing service cannot be reached or refuses the key. Set `ROUTING_PROVIDER=none` and restart the server while you fix it |
