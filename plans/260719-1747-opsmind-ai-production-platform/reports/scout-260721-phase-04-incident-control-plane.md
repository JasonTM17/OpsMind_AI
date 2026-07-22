# Phase 4 Incident Control Plane Scout

## Context

Read-only scout of the active Phase 4 plan, Platform API, canonical contracts,
PostgreSQL migrations/tests, master prompt, and active blockers on 2026-07-21.

## Current Foundation

- Spring Boot 4.1/Java 21 uses MVC, JDBC, Flyway, explicit
  `TransactionTemplate`, Java records, Jackson 3, Problem Details, and
  persistence-gated beans. There is no JPA, Lombok, or root Maven reactor.
- Phase 3 already supplies verified OIDC principal mapping, active-user checks,
  transaction-local tenant RLS, idempotency primitives, strong numeric ETags,
  exact-byte outbox events, and disposable PostgreSQL integration tests.
- `audit_events` is update/delete append-only but its digests are caller-supplied;
  continuity, per-tenant ordering, concurrency serialization, and tamper
  verification are not yet enforced.
- Canonical API/schema roots exist, but the current static validator does not
  compile JSON Schema references, validate fixtures, or dereference OpenAPI.
- V002 is already applied by Phase 3. The Phase 4 plan's Java package, Maven
  command, migration number, and disconnected evidence-store Compose file were
  stale.

## Architectural Findings

1. A flat `/incidents/{id}` lookup cannot safely establish RLS tenant context
   from an untrusted ID. Organization/project-scoped routes are the simplest
   safe contract until a narrow membership-aware resolver exists.
2. Verified OAuth scope is necessary but not tenant authority. Every write must
   re-resolve issuer/subject and active membership inside the same transaction.
3. The highest-risk provable slice is create/read/transition with incident,
   timeline, database-computed audit, outbox, and idempotency completion in one
   PostgreSQL transaction.
4. Existing request correlation accepts safe non-UUID strings while audit and
   outbox use UUID. A generated operation UUID must join durable effects; the
   external trace string remains separate metadata.
5. A tenant audit chain needs a tenant advisory transaction lock, database-
   assigned tenant sequence, database-derived previous digest, and exact stored-
   field SHA-256. Application callers cannot be trusted to supply integrity.
6. PostgreSQL and object storage cannot commit atomically. A future artifact
   lifecycle requires pending/finalized metadata, immutable writes, current
   authorization-epoch checks, tombstone-before-purge, receipts, and
   bidirectional orphan reconciliation.
7. Presigned provider URLs are bearer capabilities and cannot prove cross-tenant
   replay denial. Future reads must stream through the authenticated Platform
   API or use a principal/session-bound proxy ticket while backend URLs remain
   internal.

## Considered Approaches

| Approach | Complexity | Main consequence | Decision |
|---|---:|---|---|
| Full Phase 4 including object backend now | High | Blocked by lifecycle, privacy, and archived-MinIO decisions; encourages false proof | Reject for current checkpoint |
| Flat incident routes plus privileged tenant resolver | Medium | Preserves prompt examples but adds a new SECURITY DEFINER discovery boundary | Defer |
| Scoped authorized incident write ledger | Low-medium | Proves RLS, state, concurrency, audit, idempotency, and events with existing foundations | Select |

## Selected Checkpoint

Checkpoint 4A implements organization/project-scoped incident create, detail,
explicit transition, and timeline read. It uses the master prompt's six statuses
and four severities, database-authoritative roles, `incident:read`/
`incident:write` scopes, V003, and real PostgreSQL proof. Evidence bytes,
postmortem breadth, AI, tools, Temporal, RAG, and frontend remain out of scope.

This is the simplest viable option that proves the trust and transaction model.
It does not complete Phase 4 or G2.

## Required Verification

- Complete legal/illegal transition matrix and validation tests.
- Scope/role/revocation/deprovision/cross-tenant denial matrix.
- Exact replay and same-key/different-input conflict.
- Stale ETag and two-request one-winner concurrency proof.
- Atomic rollback when audit/outbox append fails.
- Forced RLS, append-only timeline/audit, and recomputable linear audit chain.
- Fresh and upgrade migrations without changing V001/V002.
- Offline OpenAPI/schema/fixture validation, full Maven regression, layout/docs,
  secret scan, storage preflight, and revision/source-bound local evidence.

## Unresolved Questions

- Supported evidence backend, lifecycle/restore implementation, privacy deletion
  receipts, and MinIO replacement remain tracked as B-006, B-008, and B-012.
