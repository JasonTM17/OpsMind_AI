# Phase 4: Durable Gateway and Prometheus Connector

## Goal

Replace fixture Gateway state with crash-recoverable PostgreSQL receipts/audit
and execute the first read against a real non-production Prometheus service.

## Files

- `services/tool-gateway/src/main/resources/db/migration/**`
- Tool Gateway persistence adapters/configuration/tests
- Prometheus connector, manifest, and tests
- PostgreSQL bootstrap, Compose, environment example, deployment docs

## Implementation

1. Create a dedicated `tool_gateway` schema, Flyway history, migrator, and
   non-owner runtime role. No Platform or AI Runtime table grants.
2. Store hashed capability nonce, deterministic execution identity, canonical
   request digest, bounded status/lease, normalized response, and append-only
   audit metadata. Never store tokens, credentials, raw headers, or prompts.
3. Reserve and finalize in separate short transactions; never hold a database
   transaction across Prometheus HTTP. Conditional lease tokens prevent stale
   finalization. Same execution/same digest converges; drift conflicts.
4. Implement a read-only Prometheus HTTP API connector with exact configured
   base URI, no redirects/ambient proxy, secret injection, bounded response,
   strict JSON, timeout/cancellation, series/point limits, and stable errors.
5. Map catalog IDs to server-owned PromQL templates and label values. Model and
   caller cannot provide PromQL, path, host, credential profile, or arbitrary
   query parameters.
6. Add a digest-pinned Prometheus non-production Compose profile and synthetic
   scrape/series. Readiness requires DB, JWKS, durable stores, enabled manifest,
   and connector reachability; liveness remains process-only.
7. Add migration upgrade, runtime grant, concurrency, crash-window, duplicate,
   cross-tenant, audit immutability, and cancellation gates.

## Tests

- Fresh/upgrade migrations and least-privilege role inspection.
- Reserve/finalize/reclaim races, stale lease, same-ID drift, nonce replay,
  audit failure rollback, and append-only SQL tests.
- Prometheus real-service contract plus parser boundary tests.
- Clean shutdown leaves no leased false-success record.

## Acceptance

- [x] Gateway restart/retry converges to one logical evidence response.
- [x] No DB transaction spans connector I/O.
- [x] Runtime roles cannot mutate audit history or cross service schemas.
- [x] A real non-production Prometheus query returns bounded canonical evidence.

## Checkpoint Status

Completed on commit `ace3642f9f94293e2ff10f580c6028eb997ec036`.
[GitHub Actions run 29987371420](https://github.com/JasonTM17/OpsMind_AI/actions/runs/29987371420)
passed the guarded PostgreSQL role/migration/concurrency suite, digest-pinned
Prometheus config and live query-range Compose smoke, Java dependency security,
service tests, Linux/Windows bootstrap, and cleanup.

## Rollback

Disable the Prometheus manifest/client profile. Preserve forward-only schema and
receipts; never destructively down-migrate audit state.
