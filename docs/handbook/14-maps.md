# 14 Maps and addresses

[Back to the handbook](README.md)

FoodStack uses three map services. Out of the box they are OpenStreetMap's free public services, which are fine for a trial but **allow only light use**. Before you open to the public, switch to services you are allowed to use at your volume.

| Service | What it does in FoodStack | Default | Setting |
|---|---|---|---|
| Address search (geocoding) | Customers type their area or street; staff type a restaurant's address | OpenStreetMap Nominatim, about one search a second | `GEOCODING_PROVIDER`, `GEOCODING_URL`, `GEOCODING_COUNTRY_CODES` |
| Map tiles | The map pictures in the back office (drawing delivery zones) and in live delivery tracking | OpenStreetMap's tile server, light use only | `MAP_TILE_URL`, `MAP_TILE_ORIGIN`, `MAP_ATTRIBUTION` |
| Road distances (routing) | Delivery charges by distance, rider offers, arrival times | Straight-line distance times a road factor, no outside service | `ROUTING_PROVIDER`, `ROUTING_URL` |

You can do this guide later, but before real customers arrive.

## What you need

Choose one of:

- **A hosted provider** that offers a Nominatim-compatible search API and map tiles (several companies sell this per request; some have free tiers), and their URLs and API key; or
- **Your own servers** running Nominatim, a tile server and OSRM for your country (a technical project: each needs its own machine with your country's map data). Their internal addresses.

Also: the two-letter code of your country, such as `pk` or `ae`.

## Steps

1. Open the settings file:
   ```bash
   cd /opt/foodstack
   nano .env
   ```
2. Add (or change) these lines at the end, with your provider's details:
   ```
   # Address search: only addresses in your country
   GEOCODING_PROVIDER=nominatim
   GEOCODING_URL=<your Nominatim-compatible URL>
   GEOCODING_COUNTRY_CODES=<your country code, such as pk>

   # Map tiles: the URL with {z}/{x}/{y}, its scheme and host, and the credit the provider asks for
   MAP_TILE_URL=<https://tiles.example-provider.com/{z}/{x}/{y}.png?key=...>
   MAP_TILE_ORIGIN=<https://tiles.example-provider.com>
   MAP_ATTRIBUTION=<© the provider © OpenStreetMap contributors>

   # Road distances, if you run OSRM
   ROUTING_PROVIDER=osrm
   ROUTING_URL=<http://your-osrm-server:5000>
   ```
   Leave out any part you are not changing. `MAP_TILE_ORIGIN` must be exactly the `https://host` part of `MAP_TILE_URL`, or the back office will refuse to load the tiles.
3. Restart everything that uses maps:
   ```bash
   ./restart.sh server
   ./restart.sh web
   ./restart.sh backoffice
   ```

## Check it worked

- In the customer app, type part of a street name in **Search your area or street**: suggestions appear, only in your country.
- In the back office, open a restaurant's **Delivery** tab: the map shows your provider's tiles and its credit in the corner.
- With OSRM: a delivery quote on a store page shows a distance that follows the roads (usually a little longer than before).

## Undo

Delete the lines you added, or put back the old values, and restart the same three services.

## If something goes wrong

| Problem | What to do |
|---|---|
| "Address search is not available right now. Use your location instead." | The search URL cannot be reached, or the key is wrong. Look at `./logs.sh server` for the error |
| The map is grey | `MAP_TILE_ORIGIN` does not match the host of `MAP_TILE_URL`, or the key is wrong. In the browser, F12 > Console shows the blocked address |
| Delivery quotes stopped working after turning on OSRM | The OSRM server cannot be reached from FoodStack. Set `ROUTING_PROVIDER=none` and restart while you fix it |
