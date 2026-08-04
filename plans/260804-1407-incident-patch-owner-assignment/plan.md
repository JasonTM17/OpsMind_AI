---
title: Incident patch and owner assignment
description: >-
  Add a replay-safe optimistic-concurrency PATCH contract for mutable incident
  fields and authoritative active-member owner assignment.
status: in-progress
priority: P1
branch: feature/incident-patch-owner-assignment
tags:
  - feature
  - backend
  - api
  - critical
blockedBy:
  - 260719-1747-opsmind-ai-production-platform
blocks: []
created: '2026-08-04T14:07:00+07:00'
createdBy: 'ck:plan'
source: skill
---

# Incident patch and owner assignment

## Overview

Close the next repository-owned Phase 4 breadth gap with one explicit command:
`PATCH .../incidents/{incidentId}`. The command updates only mutable incident
metadata and owner assignment, uses the existing transaction/idempotency/audit
model, and never bypasses tenant, membership, ETag, or append-only guarantees.

## Scope

- In: title, summary, severity, owner assignment/clear, reason, OpenAPI/JSON
  Schema, canonical request digest, HTTP/JDBC mutation, timeline/audit/outbox,
  focused and real-PostgreSQL tests.
- Out: status transitions, root-cause/resolution mutation, alert-link modeling,
  frontend UX, postmortems, evidence-object lifecycle, production SLO claims.

## Locked contract

- Media type is `application/merge-patch+json`; unknown fields are rejected.
- `reason` is required and bounded; at least one of `title`, `summary`,
  `severity`, or `ownerId` must be present.
- `ownerId` accepts a UUID or explicit `null` to clear assignment. A non-null
  owner must be an active membership in the authoritative organization.
- `Idempotency-Key` and a strong numeric `If-Match` are mandatory.
- PATCH cannot mutate status, resolution, tenant/project IDs, actor fields,
  timestamps, or version directly.
- A successful command increments version once and appends exactly one linked
  timeline, audit, and outbox event; replay adds no effects.

## Phases

| Phase | Name | Status |
|---|---|---|
| 1 | [Contract and threat boundary](./phase-01-contract-and-threat-boundary.md) | In progress |
| 2 | [Transactional command and persistence](./phase-02-transactional-command-and-persistence.md) | Pending |
| 3 | [PostgreSQL proof and gate reconciliation](./phase-03-postgresql-proof-and-gate-reconciliation.md) | Pending |

## Dependencies

- Parent: `260719-1747-opsmind-ai-production-platform` Phase 4.
- Reuses V003 `incidents.owner_id`, forced RLS, membership FK, idempotency,
  timeline/audit/outbox appenders, and optimistic concurrency.
- No schema migration is planned unless implementation proves the existing
  constraints cannot express the locked contract.

## Acceptance Criteria

- Contract rejects empty, unknown, authority-bearing, malformed, and oversized
  PATCH bodies before mutation.
- Active same-organization owner assign and explicit clear succeed; missing,
  inactive, and foreign-organization owners fail without durable growth.
- Exact replay returns identical body, ETag, and operation ID; changed body,
  actor, path, or version conflicts.
- Missing/malformed/stale ETag and concurrent same-version commands produce no
  partial timeline/audit/outbox/idempotency effects.
- Existing create/read/list/transition/closure contracts remain compatible.
- Static contracts, focused Maven tests, real PostgreSQL trust contracts, full
  PR Quality, cross-service evaluation, and CodeQL pass on one exact revision.

## Risks and rollback

- JSON null-versus-absent is security-relevant for owner clear. Use an explicit
  presence model and test serialization/deserialization at the HTTP boundary.
- Owner membership may be revoked between validation and update. Resolve and
  mutate inside one transaction; retain the database FK as defense in depth.
- Additive code/contracts can be reverted before merge. No applied migration is
  edited or renamed.

## Unresolved Questions

- Alert-link semantics remain a separate Phase 4 child slice because no
  canonical alert identity/provider contract exists yet.
