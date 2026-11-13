# 11 Point your domain at the server

[Back to the handbook](README.md)

Customers reach your marketplace by name, not by IP address. FoodStack uses three names:

| Name | Example | What opens there |
|---|---|---|
| Your domain | `example.com` | The customer app: search, menus, ordering |
| Admin | `admin.example.com` | The back office for you, your staff, restaurants and riders |
| API | `api.example.com` | The server the apps talk to, and sign-in |

You can use different names (for example `order.example.com`), as long as they share the same main domain, so sign-in works across them.

## What you need

- A domain you own, bought from a registrar (for example Namecheap, Cloudflare, GoDaddy, or your country's registry).
- Your server's IP address from [guide 10](10-server.md).
- About 10 minutes, then up to an hour of waiting for the names to spread.

## Steps

1. Sign in to the company that manages your domain's **DNS** (usually the registrar; sometimes Cloudflare).
2. Open the DNS records page of your domain.
3. Add (or change) these three **A records**, each pointing at your server's IP address:

   | Type | Name (or Host) | Value (or Points to) | TTL |
   |---|---|---|---|
   | A | `@` | `<server IP address>` | 300 or "Auto" |
   | A | `admin` | `<server IP address>` | 300 or "Auto" |
   | A | `api` | `<server IP address>` | 300 or "Auto" |

   `@` means the domain itself. If your provider gave the server an IPv6 address too, add the same three names as **AAAA** records with that address.
4. On Cloudflare only: set each record to **DNS only** (grey cloud), not proxied, for now. FoodStack gets its own certificates; you can turn Cloudflare's proxy on later as a CDN ([guide 49](49-cdn.md)).
5. Save, and wait. Most changes are seen within minutes, some take an hour.

## Check it worked

On your own computer:

```bash
nslookup example.com
nslookup admin.example.com
nslookup api.example.com
```

Each answer ends with `Address: <server IP address>`. If one shows another address or "can't find", wait a while and try again.

## Undo

Delete the three records, or point them back where they were.

## If something goes wrong

| Problem | What to do |
|---|---|
| The old address still shows after an hour | Your computer or network may remember the old answer. Try `nslookup example.com 1.1.1.1`, which asks Cloudflare's public DNS directly |
| The registrar has no DNS page | It uses another company's name servers. Its "Nameservers" setting says which; make the records there |
| You already have a website on `example.com` | Use other names for FoodStack, for example `order.example.com`, `admin.order.example.com` and `api.order.example.com`, and use those in your settings ([guide 14](14-install.md)) |
