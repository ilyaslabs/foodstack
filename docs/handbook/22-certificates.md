# 22 Certificates (the padlock)

[Back to the handbook](README.md)

The padlock in the browser means the connection to your site is private. It needs a **certificate** for each of your addresses, and certificates expire after about three months. FoodStack gets and renews them for you, free, from Let's Encrypt. Usually you never think about it; this guide is for when a browser warns "Your connection is not private".

## What you need

- Your domain pointing at the server ([guide 11](11-domain-and-dns.md)).
- Ports 80 and 443 open on the server's firewall and in your provider's control panel ([guide 10](10-server.md)). Let's Encrypt checks you own the domain by visiting port 80.

## How it works

- **One server**: the front door (Caddy, the service called `proxy`) asks Let's Encrypt for certificates for `example.com`, `admin.example.com` and `api.example.com` the first time it starts, and renews each one about a month before it expires. Nothing to set up.
- **Kubernetes**: cert-manager does the same. You choose the issuer once in your values (`gateway.tls.certManager.issuer`); see [guide 40](40-move-to-kubernetes.md).

## Steps

There is nothing to do while it works. To check a certificate:

1. Open `https://admin.example.com/admin/`, click the padlock (or the settings icon left of the address), then **Connection is secure > Certificate is valid**. It shows who issued it (Let's Encrypt) and when it expires.
2. Or, on the server:
   ```bash
   echo | openssl s_client -connect admin.example.com:443 -servername admin.example.com 2>/dev/null | openssl x509 -noout -issuer -enddate
   ```

### If a certificate did not arrive or renew

3. Look at what the front door says:
   ```bash
   cd /opt/foodstack
   ./logs.sh proxy
   ```
   Press Ctrl+C to stop. Look for lines with `error` and `certificate` or `acme`.
4. Fix the cause (table below), then restart the front door, which tries again at once:
   ```bash
   ./restart.sh proxy
   ```

### Moving to another domain

5. The addresses are in `/opt/foodstack/.env`, in the lines `FOODSTACK_DOMAIN`, `FOODSTACK_ADMIN_DOMAIN`, `FOODSTACK_API_DOMAIN`, `FOODSTACK_SITES`, `FOODSTACK_HOSTNAMES`, `FOODSTACK_ADMIN_ROOT_HOST`, `FOODSTACK_PUBLIC_URL`, `FOODSTACK_WEB_URL` and `FOODSTACK_ADMIN_URL`. Point the new domain at the server first ([guide 11](11-domain-and-dns.md)), change the old name to the new one on every one of those lines, and run `./restart.sh` (everything restarts, about a minute offline). Then update the Stripe webhook address ([guide 15](15-card-payments.md)) and the Google and Apple sign-in settings, which name your domain too.

## Check it worked

- All three addresses open with a padlock and no warning, in a private window.
- On Kubernetes: `kubectl -n foodstack get certificate` shows `READY True` for each.

## Undo

Nothing to undo: certificates are renewed by themselves. Restarting the proxy is safe at any time; it is offline for a second or two.

## If something goes wrong

| What the log or browser says | Cause and fix |
|---|---|
| "Timeout during connect" or "Connection refused" | Port 80 or 443 is closed. `sudo ufw status` must list both, and so must the provider's firewall |
| "DNS problem: NXDOMAIN" or the wrong IP address | The domain does not point at this server yet. Check with `nslookup admin.example.com`; wait for DNS ([guide 11](11-domain-and-dns.md)) |
| "too many certificates already issued" or "rateLimited" | Let's Encrypt allows only a few certificates per week for the same names. This happens after many reinstalls. Wait the time it says (up to a week), then `./restart.sh proxy`. Test with `--local` on your laptop rather than reinstalling the real server |
| Cloudflare: the site loops or shows error 525/526 | The orange cloud is on. Set the records to **DNS only** (grey cloud) ([guide 11](11-domain-and-dns.md)) |
| Kubernetes: `READY False` | `kubectl -n foodstack describe certificate` and `kubectl -n cert-manager logs deploy/cert-manager` say why; the causes are the same as above. The **CertificateExpiring** alert warns 14 days ahead |
