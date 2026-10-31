# FoodStack front-end

Angular workspace (pnpm) with two apps and shared libraries (TDD section 14).

| Project | What |
|---|---|
| `projects/web` | Customer app, server-side rendered (Node). M0: branded landing page and sign-in |
| `projects/backoffice` | Back office under `/admin/`. M0: sign-in, overview, branding (name, colours, font, logo upload) |
| `projects/ui` | Runtime branding: loads `/api/v1/settings/branding` and writes Material 3 colour tokens; brand header |
| `projects/auth` | Authorization code + PKCE sign-in, token interceptor, route guard, callback page |
| `projects/api-client` | Typed clients for `/api/v1` and the runtime `config.json` loader |

## Run locally

1. Start the server with containers: run `LocalFoodstackApplication` (in `app/foodstack-server/src/test`).
2. `pnpm install`, then `pnpm start:web` (http://localhost:4200) or `pnpm start:backoffice` (http://localhost:4300/admin/).
   API, login and token calls are proxied to `localhost:8080` (`proxy.conf.json`).
3. Sign in to the back office as `admin@local.foodstack.local` / `foodstack-local`, or any demo user from the test seed.

## Test and build

- `pnpm test` runs the Vitest unit tests of every project.
- `pnpm build` builds both apps into `dist/`.

## Runtime configuration

Each container serves `config.json` from environment variables, so one image works for every operator:

| Variable | Used by | Meaning |
|---|---|---|
| `FOODSTACK_API_URL` | web, backoffice | Public API base URL; empty for the same origin (default behind Caddy or the Gateway) |
| `FOODSTACK_ISSUER` | web, backoffice | OIDC issuer; empty for the same origin |
| `FOODSTACK_INTERNAL_API_URL` | web (SSR) | API URL on the internal network for server-side rendering, for example `http://server:8080` |
| `FOODSTACK_ALLOWED_HOSTS` | web (SSR) | Comma-separated host names the SSR server answers for |
