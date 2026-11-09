# ilyasdotdev/ilyaslabs-foodstack:backoffice-<version>: the back office as static files under /admin/ (TDD 14.3).
# Build after `pnpm build`:  docker build -f docker/backoffice.Dockerfile -t ilyasdotdev/ilyaslabs-foodstack:backoffice-dev frontend
# FOODSTACK_API_URL (empty = same origin) and the map tile settings are written into /admin/config.json at start;
# MEDIA_ORIGINS (the buckets' addresses) joins the page's security policy.
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY docker/backoffice.conf.template /etc/nginx/templates/default.conf.template
COPY dist/backoffice/browser/ /usr/share/nginx/html/admin/
ENV FOODSTACK_API_URL="" \
    MAP_TILE_URL="https://tile.openstreetmap.org/{z}/{x}/{y}.png" \
    MAP_TILE_ORIGIN="https://tile.openstreetmap.org" \
    MAP_ATTRIBUTION="© OpenStreetMap contributors" \
    MEDIA_ORIGINS=""
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=10 CMD wget -qO- http://localhost:8080/admin/config.json || exit 1
