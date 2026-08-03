---
phase: 2
title: Runtime Query Path
status: completed
priority: P1
dependencies:
  - 1
---

# Phase 2: Runtime Query Path

## Overview

Implement the authorized, read-only collection query using existing transaction,
scope, membership, RLS, and error patterns.

## Requirements

- Require `incident:read`, active membership, permitted read role, and tenant context.
- Perform no persistent writes; do not mark the transaction database read-only
  because membership authorization uses the existing `FOR SHARE` lock.
- Order: authenticate/scope + syntactic input validation; transaction; active
  user; tenant context; locked membership/project authorization; semantic
  cursor path/filter binding; list SELECT.
- Use `pageSize + 1` and the strict V016-backed keyset predicate.
- Normalize absent status distinctly from exact enum status in the cursor contract.
- Cursor is unsigned, opaque-by-contract, non-secret, and forgeable as a bounded
  navigation hint. Tampering may choose only an authorized indexed seek point.

## Architecture

Use a separate controller, repository, and service; do not expand the existing
288-line controller or mutation repository. Canonical parsing may precede the
transaction, but context-binding decisions happen only after authorization.
Return immutable records and `Cache-Control: no-store`.

## Related Code Files

- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentListController.java`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentListPageToken.java`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentListQueryService.java`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentListRepository.java`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/JdbcIncidentListRepository.java`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentSummary.java`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentListPage.java`
- Create: `services/platform-api/src/main/resources/db/migration/V016__incident_list_pagination_indexes.sql`
- Create: `services/platform-api/src/main/resources/db/migration/V016__incident_list_pagination_indexes.sql.conf`

## Implementation Steps

1. Add forward-only V016 indexes `(organization_id, project_id, updated_at DESC,
   id DESC)` and `(organization_id, project_id, status, updated_at DESC, id DESC)`
   with `CREATE INDEX CONCURRENTLY` and `executeInTransaction=false`. Roll out
   V016 successfully before enabling the new runtime query.
2. Implement strict canonical token parse and post-authorization binding with
   versioned organization, project, normalized status, updated-at microseconds, and ID.
3. Add repository queries for unfiltered and exact-status pages, both explicitly
   scoped by organization/project and ordered by the stable tuple.
4. Add transaction service: validate scope/IDs/page size, authorize and bind RLS,
   query `pageSize + 1`, trim, and encode the next tuple.
5. Wire the separate collection GET controller and no-store response without
   changing the existing collection POST controller.
6. Map persistence failures to sanitized existing Problem Details; never return
   a partial page or token.

## Success Criteria

- [ ] Authorized page and exact-status filter return deterministic summaries only.
- [ ] Foreign tenant/project/filter tokens and revoked membership reveal no records.
- [ ] Tied timestamps produce no duplicate/omitted rows over an unchanged dataset.
- [ ] Denied authorization prevents semantic token binding and the list SELECT.
- [ ] Before/after snapshots prove no incident, timeline, audit, outbox, or
  idempotency state mutation on success or any error path.
- [ ] Existing endpoints stay byte/behavior compatible.
- [ ] V016 failure is repairable/retryable and both indexes are `indisvalid` and
  `indisready` before the list runtime is admitted.

## Risk Assessment

Mutable `updatedAt` means live traversal may omit or duplicate rows changed
between requests, including clock-regression cases. Contract it as live view,
test controlled concurrent updates, and guarantee determinism only for an
unchanged dataset.
