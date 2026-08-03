---
title: Incident resolution to closure verification
description: >-
  Prove the existing incident RESOLVED to CLOSED lifecycle across contracts,
  HTTP, persistence, idempotency, and immutable event effects.
status: in-progress
priority: P1
branch: feature/incident-closure-verification
tags:
  - feature
  - backend
  - api
  - critical
blockedBy:
  - 260719-1747-opsmind-ai-production-platform
blocks: []
created: '2026-08-03T17:09:11.385Z'
createdBy: 'ck:plan'
source: skill
---

# Incident resolution to closure verification

## Overview

Complete the missing proof for the already implemented incident closure path.
No new route, migration, or product semantics are introduced. The slice binds
contract fixtures to the public transition API and proves that closure retains
resolution fields, is replay-safe, rejects stale or post-close writes, and
does not create partial timeline, audit, outbox, or idempotency effects.

## Scope

- In: closure fixtures/schema invariant, controller binding, real PostgreSQL HTTP lifecycle proof, exact-head CI evidence.
- Out: generic patch, owner/alert assignment, postmortems, artifact lifecycle.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Contract fixtures and closure semantics](./phase-01-contract-fixtures-and-closure-semantics.md) | Completed |
| 2 | [HTTP and PostgreSQL lifecycle proof](./phase-02-http-and-postgresql-lifecycle-proof.md) | Completed |
| 3 | [Verification reconciliation and gate proof](./phase-03-verification-reconciliation-and-gate-proof.md) | In Progress |

## Dependencies

- Parent: `260719-1747-opsmind-ai-production-platform` Phase 4.
- Reuses the existing `POST .../{incidentId}/transitions` contract and V003 state machine.
- Heavy local Maven/PostgreSQL runs remain capacity-gated; exact-head CI is authoritative while C:/D: are below thresholds.

## Acceptance Criteria

- CLOSED request omits resolution fields while response/detail/timeline retain the authoritative resolved values.
- Exact replay returns identical body, ETag, and operation ID with no new effects.
- Stale ETag and every post-close transition fail without timeline/audit/outbox growth.
- Static contracts, focused tests, PostgreSQL trust contracts, PR quality, cross-service evaluation, and CodeQL pass on one revision.
