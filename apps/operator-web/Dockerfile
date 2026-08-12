# syntax=docker/dockerfile:1.7
FROM node:26.7.0-bookworm-slim@sha256:cd565714d4da3e84bfd341e31448f81d47c6362198f152345297c9c1154e6341 AS dependencies
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

FROM node:26.7.0-bookworm-slim@sha256:cd565714d4da3e84bfd341e31448f81d47c6362198f152345297c9c1154e6341 AS runtime
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
WORKDIR /app
USER node
COPY --from=build --chown=node:node /workspace/apps/operator-web/.next/standalone ./
COPY --from=build --chown=node:node /workspace/apps/operator-web/.next/static ./apps/operator-web/.next/static
CMD ["node", "apps/operator-web/server.js"]
