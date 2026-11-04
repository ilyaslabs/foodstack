# ilyaslabs/foodstack-proxy: Caddy with its rate-limit plugin, the single-server gateway (TDD 17.2).
# The unit deployer runs it with config/Caddyfile, pulling it when published or building it here.
FROM caddy:2.10-builder-alpine AS build
RUN xcaddy build --with github.com/mholt/caddy-ratelimit@v0.1.0

FROM caddy:2.10-alpine
COPY --from=build /usr/bin/caddy /usr/bin/caddy
