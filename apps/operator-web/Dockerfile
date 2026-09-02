# syntax=docker/dockerfile:1.7
FROM node:26.8.1-bookworm-slim@sha256:367679cf9792759492a486e4aa4b421764d71a9546a6dae8aab81a99eb797b3e AS dependencies
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

FROM node:26.8.1-bookworm-slim@sha256:367679cf9792759492a486e4aa4b421764d71a9546a6dae8aab81a99eb797b3e AS runtime
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
WORKDIR /app
USER node
COPY --from=build --chown=node:node /workspace/apps/operator-web/.next/standalone ./
COPY --from=build --chown=node:node /workspace/apps/operator-web/.next/static ./apps/operator-web/.next/static
CMD ["node", "apps/operator-web/server.js"]
