---
title: Incident List Pagination
description: >-
  Add a tenant-scoped incident collection read with exact status filtering and
  deterministic live-view keyset pagination.
status: completed
priority: P1
branch: feature/incident-list-pagination
tags:
  - feature
  - backend
  - api
  - auth
blockedBy: []
blocks: []
created: '2026-08-03T11:44:33.937Z'
createdBy: 'ck:plan'
source: skill
---

# Incident List Pagination

## Overview

Advance the A-to-Z Phase 4 control plane with the smallest unblocked public API
slice: authorized incident listing. The route returns a bounded six-field
summary page ordered by `(updatedAt DESC, id DESC)`. A canonical versioned cursor
is bound to organization, project, normalized status filter, sort contract, and
last tuple. The cursor is unsigned, forgeable, non-secret, and never authorization.

## Scope Boundary

- In: collection `GET`, optional exact `status`, page size 1..100, opaque keyset
  cursor, additive V016 indexes, tenant/RLS isolation, static/unit/HTTP/PostgreSQL/CI evidence.
- Out: free-text search, severity filtering, snapshot/lossless pagination, mutations,
  owner/alert assignment, artifact references, and postmortems.
- Preserve create/detail/transition/timeline behavior and all public v1 contracts.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Contract and Tests](./phase-01-contract-and-tests.md) | Completed |
| 2 | [Runtime Query Path](./phase-02-runtime-query-path.md) | Completed |
| 3 | [Verification and Delivery](./phase-03-verification-and-delivery.md) | Completed |

## Dependencies

- Parent: `../260719-1747-opsmind-ai-production-platform/plan.md`, Phase 4.
- Adds V016 indexes matching filtered and unfiltered descending tuple queries;
  historical migrations remain byte-identical.
- Does not depend on blocked artifact lifecycle Phase 4C or external B-006/B-008/B-012 evidence.

## Acceptance Summary

- Authorized reads return only current organization/project incidents.
- Tied timestamps paginate without duplicate rows over an unchanged dataset;
  cursor/path/filter mismatch fails `400` after authorization.
- Invalid status/page inputs fail at the HTTP boundary; invisible scope stays non-enumerating.
- Listing performs no audit, timeline, outbox, or idempotency write.
- Focused and full available gates pass; remote revision-bound CI supplies blocked heavy evidence.
