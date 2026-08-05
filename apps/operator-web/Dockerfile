# syntax=docker/dockerfile:1.7
FROM node:26.5.1-bookworm-slim@sha256:9e6f9357d371591e32ab6f2d8a26d63bdd0d17c29eee3f4f3e7e454d9634bf73 AS dependencies
ENV PNPM_HOME=/pnpm
ENV PATH=$PNPM_HOME:$PATH
RUN corepack enable
WORKDIR /workspace
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/operator-web/package.json apps/operator-web/package.json
RUN --mount=type=cache,target=/pnpm/store \
    pnpm config set store-dir /pnpm/store && \
    pnpm install --frozen-lockfile --filter @opsmind/operator-web...

FROM dependencies AS build
COPY apps/operator-web apps/operator-web
RUN pnpm --filter @opsmind/operator-web build

FROM node:26.5.1-bookworm-slim@sha256:9e6f9357d371591e32ab6f2d8a26d63bdd0d17c29eee3f4f3e7e454d9634bf73 AS runtime
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
WORKDIR /app
USER node
COPY --from=build --chown=node:node /workspace/apps/operator-web/.next/standalone ./
COPY --from=build --chown=node:node /workspace/apps/operator-web/.next/static ./apps/operator-web/.next/static
CMD ["node", "apps/operator-web/server.js"]
