# ilyaslabs/foodstack-backoffice: the back office as static files under /admin/ (TDD 14.3).
# Build after `pnpm build`:  docker build -f docker/backoffice.Dockerfile -t ilyaslabs/foodstack-backoffice frontend
# FOODSTACK_API_URL and FOODSTACK_ISSUER (empty = same origin) are written into /admin/config.json at start.
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY docker/backoffice.conf.template /etc/nginx/templates/default.conf.template
COPY dist/backoffice/browser/ /usr/share/nginx/html/admin/
ENV FOODSTACK_API_URL="" FOODSTACK_ISSUER=""
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=10 CMD wget -qO- http://localhost:8080/admin/config.json || exit 1
