# ilyaslabs/foodstack-web: the customer app's SSR server (TDD 14.3). Build after `pnpm build`:
#   docker build -f docker/web.Dockerfile -t ilyaslabs/foodstack-web frontend
FROM node:24-alpine
WORKDIR /app
COPY --chown=node:node dist/web/ ./
USER node
ENV NODE_ENV=production PORT=4000
EXPOSE 4000
HEALTHCHECK --interval=10s --timeout=5s --retries=10 CMD wget -qO- http://localhost:4000/healthz || exit 1
CMD ["node", "server/server.mjs"]
